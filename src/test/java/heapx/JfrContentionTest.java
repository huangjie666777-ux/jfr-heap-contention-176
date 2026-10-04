package heapx;

import heapx.http.Api;
import heapx.jfr.ContentionEvent;
import heapx.jfr.ContentionQuery;
import heapx.jfr.JfrParser;
import heapx.service.AnalysisService;
import io.javalin.Javalin;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JfrContentionTest {

    /** Records real monitor contention in this JVM: a worker holds a lock
     *  while the main thread blocks on it. Returns the JFR file plus the
     *  Java thread ids of the blocked (main) thread. */
    static Path recordContention(Path dir) throws Exception {
        Object lock = new Object();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            synchronized (lock) {
                holding.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {}
            }
        });
        Path jfr = dir.resolve("contention.jfr");
        try (Recording rec = new Recording()) {
            rec.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ZERO);
            rec.start();
            worker.start();
            assertTrue(holding.await(5, TimeUnit.SECONDS));
            Thread.sleep(50);
            synchronized (lock) { // blocks until the worker releases -> contention event
                // entered
            }
            release.countDown();
            worker.join(5000);
            rec.stop();
            rec.dump(jfr);
        }
        return jfr;
    }

    /** HPROF with java.lang.Thread instances carrying declared long 'tid'.
     *  tids[i] is written to object id (i+1); duplicateTid adds a second
     *  Thread object (id 100) with tids[0] to create ambiguity. */
    static Path buildThreadDump(Path dir, long[] tids, boolean duplicateTid) throws Exception {
        HprofWriter w = new HprofWriter();
        w.defineClass(200, "java.lang.Thread", 0).field("tid", HprofWriter.T_LONG);
        w.defineClass(201, "demo.WorkerThread", 200); // subclass inherits tid
        for (int i = 0; i < tids.length; i++) {
            w.instance(i + 1, 200, tids[i]);
            w.jniGlobalRoot(i + 1);
        }
        if (duplicateTid && tids.length > 0) {
            w.instance(100, 201, tids[0]);
            w.jniGlobalRoot(100);
        }
        return w.write(dir.resolve("threads.hprof"));
    }

    @Test
    void parseRealJfrRecording() throws Exception {
        Path dir = Files.createTempDirectory("heapx-jfr");
        Path jfr = recordContention(dir);
        List<ContentionEvent> events = JfrParser.parse(jfr);
        assertFalse(events.isEmpty(), "expected at least one JavaMonitorEnter event");
        ContentionEvent e = events.stream()
                .filter(ev -> ev.javaThreadId() == Thread.currentThread().getId())
                .findFirst().orElseThrow(() -> new AssertionError(
                        "no contention event for the main thread in " + events));
        assertTrue(e.endNanos() > e.startNanos());
        assertEquals("java.lang.Object", e.monitorClass());
        assertFalse(e.stack().isEmpty());
    }

    @Test
    void clipThenMergeWindowSemantics() {
        List<ContentionEvent> events = List.of(
                new ContentionEvent(100, 200, 1, "A", List.of()),
                new ContentionEvent(150, 250, 1, "A", List.of()),   // overlaps e1
                new ContentionEvent(500, 600, 1, "B", List.of()));  // outside window
        List<ContentionQuery.ClippedEvent> clipped = ContentionQuery.clip(events, 120, 220);
        assertEquals(2, clipped.size());
        assertEquals(80, clipped.get(0).durationNanos());  // [120,200)
        assertEquals(70, clipped.get(1).durationNanos());  // [150,220)
        // clipped first, then merged: union is [120,220) = 100, not 80+70
        long union = ContentionQuery.unionNanos(clipped.stream()
                .map(c -> new long[]{c.clipStart(), c.clipEnd()}).toList());
        assertEquals(100, union);
        // half-open: an event ending exactly at 'from' does not intersect
        assertTrue(ContentionQuery.clip(
                List.of(new ContentionEvent(50, 120, 1, "A", List.of())), 120, 220).isEmpty());
    }

    @Test
    void httpUploadWindowQueryAndDelete() throws Exception {
        Path dir = Files.createTempDirectory("heapx-jfr-http");
        Path jfr = recordContention(dir);
        long mainTid = Thread.currentThread().getId();
        Path dump = buildThreadDump(dir, new long[]{mainTid}, false);

        Javalin app = Api.create(new AnalysisService(), 0);
        String base = "http://localhost:" + app.port();
        HttpClient http = HttpClient.newHttpClient();
        try {
            HttpResponse<String> up = http.send(HttpRequest.newBuilder(URI.create(base + "/api/analyses"))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofFile(dump)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, up.statusCode(), up.body());
            String analysisId = up.body().replaceAll(".*\"analysisId\"\\s*:\\s*\"([^\"]+)\".*", "$1");

            HttpResponse<String> rec = http.send(HttpRequest.newBuilder(
                    URI.create(base + "/api/analyses/" + analysisId + "/recordings"))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofFile(jfr)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, rec.statusCode(), rec.body());
            String recordingId = rec.body().replaceAll(".*\"recordingId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            String firstStart = rec.body().replaceAll(".*\"firstEventStart\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            String lastEnd = rec.body().replaceAll(".*\"lastEventEnd\"\\s*:\\s*\"([^\"]+)\".*", "$1");

            HttpResponse<String> q = http.send(HttpRequest.newBuilder(URI.create(
                    base + "/api/analyses/" + analysisId + "/recordings/" + recordingId
                            + "/contention?from=" + firstStart + "&to=" + lastEnd)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, q.statusCode(), q.body());
            assertTrue(q.body().contains("\"monitorClass\":\"java.lang.Object\""), q.body());
            assertTrue(q.body().contains("\"status\":\"matched\""), q.body());
            assertTrue(q.body().contains("\"objectId\":\"0x1\""), q.body());
            assertTrue(q.body().contains("\"javaThreadId\":" + mainTid), q.body());
            assertTrue(q.body().contains("\"waitNanos\":"), q.body());

            // window that excludes everything
            HttpResponse<String> empty = http.send(HttpRequest.newBuilder(URI.create(
                    base + "/api/analyses/" + analysisId + "/recordings/" + recordingId
                            + "/contention?from=1970-01-01T00:00:00Z&to=1970-01-01T00:00:01Z"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, empty.statusCode());
            assertTrue(empty.body().contains("\"events\":[]"), empty.body());

            // bad window rejected
            HttpResponse<String> bad = http.send(HttpRequest.newBuilder(URI.create(
                    base + "/api/analyses/" + analysisId + "/recordings/" + recordingId
                            + "/contention?from=" + lastEnd + "&to=" + firstStart)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, bad.statusCode());

            // delete removes analysis + recording
            http.send(HttpRequest.newBuilder(URI.create(base + "/api/analyses/" + analysisId))
                    .DELETE().build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> gone = http.send(HttpRequest.newBuilder(URI.create(
                    base + "/api/analyses/" + analysisId + "/recordings/" + recordingId
                            + "/contention?from=" + firstStart + "&to=" + lastEnd)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, gone.statusCode());
        } finally {
            app.stop();
        }
    }

    @Test
    void unmatchedAndAmbiguousTids() throws Exception {
        Path dir = Files.createTempDirectory("heapx-jfr-match");
        Path jfr = recordContention(dir);
        long mainTid = Thread.currentThread().getId();
        // duplicate main tid -> ambiguous; nothing else -> other threads unmatched
        Path dump = buildThreadDump(dir, new long[]{mainTid}, true);

        AnalysisService svc = new AnalysisService();
        heapx.service.Analysis a;
        try (var in = Files.newInputStream(dump)) { a = svc.analyze(in); }
        heapx.service.Recording r;
        try (var in = Files.newInputStream(jfr)) { r = svc.addRecording(a.id, in); }
        assertNotNull(r);
        assertFalse(r.events.isEmpty());
        // tid present twice in the heap -> ambiguous, event kept
        assertEquals(2, a.model.threadTids.get(mainTid).size());
        // a tid absent from the heap -> unmatched (covered in API layer)
        assertNull(a.model.threadTids.get(mainTid + 999999));
        svc.delete(a.id);
        assertNull(svc.getRecording(a.id, r.id));
        assertFalse(Files.exists(r.source));
    }

    @Test
    void corruptJfrRejected() throws Exception {
        Path dir = Files.createTempDirectory("heapx-jfr-corrupt");
        Path dump = buildThreadDump(dir, new long[]{1}, false);
        AnalysisService svc = new AnalysisService();
        heapx.service.Analysis a;
        try (var in = Files.newInputStream(dump)) { a = svc.analyze(in); }
        byte[] garbage = "this is not a JFR file at all".getBytes();
        try {
            svc.addRecording(a.id, new java.io.ByteArrayInputStream(garbage));
            fail("corrupt JFR must be rejected");
        } catch (heapx.parse.AnalysisException e) {
            assertEquals(400, e.status);
        }
        svc.delete(a.id);
    }
}
