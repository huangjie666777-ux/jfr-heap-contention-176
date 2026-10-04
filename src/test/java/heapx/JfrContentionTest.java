package heapx;

import heapx.http.Api;
import heapx.jfr.ContentionEvent;
import heapx.jfr.WindowAggregator;
import heapx.parse.AnalysisException;
import heapx.parse.HprofParser;
import heapx.service.Analysis;
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
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class JfrContentionTest {

    /** Records a real JFR file with genuine monitor contention from three
     *  worker threads; fills tids[0..2] with their Java thread ids. */
    static Path recordContention(Path dir, long[] tids) throws Exception {
        Object lock = new Object();
        AtomicBoolean failed = new AtomicBoolean();
        Runnable work = () -> {
            for (int i = 0; i < 20; i++) {
                try { Thread.sleep(1); } catch (InterruptedException e) { failed.set(true); }
                synchronized (lock) {
                    try { Thread.sleep(5); } catch (InterruptedException e) { failed.set(true); }
                }
            }
        };
        Thread[] workers = new Thread[3];
        Path out = dir.resolve("contention.jfr");
        try (Recording r = new Recording()) {
            r.enable("jdk.JavaMonitorEnter").with("threshold", "0 ms");
            r.start();
            for (int i = 0; i < workers.length; i++) {
                workers[i] = new Thread(work, "worker-" + i);
                workers[i].start();
            }
            for (Thread t : workers) t.join();
            r.stop();
            r.dump(out);
        }
        assertFalse(failed.get());
        for (int i = 0; i < workers.length; i++) tids[i] = workers[i].getId();
        return out;
    }

    /** HPROF with java.lang.Thread objects: tidA unique (subclass instance),
     *  tidB declared by two objects (ambiguous), tidC absent, plus an
     *  event-less thread object. All thread objects are JNI roots. */
    static Path buildThreadDump(Path dir, long tidA, long tidB) throws Exception {
        HprofWriter w = new HprofWriter();
        w.defineClass(200, "java.lang.Thread", 0)
                .field("tid", HprofWriter.T_LONG);
        w.defineClass(201, "demo.WorkerThread", 200); // subclass of Thread
        w.defineClass(110, "byte[]", 0);
        w.instance(20, 201, tidA);       // subclass instance, unique tid
        w.instance(21, 200, tidB);       // duplicate tid #1
        w.instance(22, 200, tidB);       // duplicate tid #2 -> ambiguous
        w.instance(23, 200, 424242L);    // no JFR events for this tid
        w.jniGlobalRoot(20);
        w.jniGlobalRoot(21);
        w.jniGlobalRoot(22);
        w.jniGlobalRoot(23);
        return w.write(dir.resolve("threads.hprof"));
    }

    @Test
    void threadIndexFromDump() throws Exception {
        Path dir = Files.createTempDirectory("heapx-tidx");
        Path dump = buildThreadDump(dir, 1001L, 1002L);
        HprofParser.ParseResult parsed = new HprofParser(50000, 200000).parseFull(dump.toFile());
        var idx = parsed.threads();
        assertEquals(4, idx.threadObjects);
        assertEquals(0, idx.missingTid);
        assertEquals(20L, idx.objectIdOf(1001L));
        assertNull(idx.objectIdOf(1002L));
        assertEquals(heapx.model.ThreadIndex.Status.AMBIGUOUS, idx.statusOf(1002L));
        assertEquals(heapx.model.ThreadIndex.Status.MATCHED, idx.statusOf(1001L));
        assertEquals(heapx.model.ThreadIndex.Status.UNMATCHED, idx.statusOf(9999L));
        assertEquals(heapx.model.ThreadIndex.Status.MATCHED, idx.statusOf(424242L));
    }

    @Test
    void threadIndexMissingTidField() throws Exception {
        Path dir = Files.createTempDirectory("heapx-notid");
        HprofWriter w = new HprofWriter();
        w.defineClass(200, "java.lang.Thread", 0)
                .field("name", HprofWriter.T_OBJECT); // no tid field at all
        w.defineClass(110, "byte[]", 0);
        w.instance(20, 200, 0);
        w.jniGlobalRoot(20);
        Path dump = w.write(dir.resolve("notid.hprof"));
        var parsed = new HprofParser(50000, 200000).parseFull(dump.toFile());
        assertEquals(1, parsed.threads().threadObjects);
        assertEquals(1, parsed.threads().missingTid);
        assertEquals(0, parsed.threads().tidToObjectId.size());
    }

    @Test
    void windowClipThenMerge() {
        Instant t0 = Instant.parse("2026-10-04T00:00:00Z");
        List<ContentionEvent> events = List.of(
                ev(t0, 0, 100, 1, "A"),
                ev(t0, 50, 100, 1, "A"),   // overlaps e1 -> union [0,150)
                ev(t0, 200, 100, 1, "B"),
                ev(t0, 400, 100, 2, "A"),
                ev(t0, 300, 100, 3, "A")); // starts exactly at window end -> excluded
        Instant from = t0.plusNanos(75);
        Instant to = t0.plusNanos(300);
        WindowAggregator.Result res = WindowAggregator.query(events, from, to);
        assertEquals(3, res.events().size());
        // clipped durations: 25, 75, 100
        assertEquals(25, res.events().get(0).nanos());
        assertEquals(75, res.events().get(1).nanos());
        assertEquals(100, res.events().get(2).nanos());
        assertEquals(1, res.threads().size()); // only tid 1 intersects the window
        WindowAggregator.ThreadAgg t1 = res.threads().stream()
                .filter(t -> t.javaThreadId() == 1).findFirst().orElseThrow();
        assertEquals(3, t1.events());
        // clip first: [75,100),[75,150) merge to 75; [200,300) = 100 -> 175 total
        assertEquals(175, t1.waitNanos());
        var lockA = t1.locks().stream().filter(l -> l.monitorClass().equals("A")).findFirst().orElseThrow();
        assertEquals(75, lockA.waitNanos()); // overlap not double counted
        assertEquals(2, lockA.events());
        var lockB = t1.locks().stream().filter(l -> l.monitorClass().equals("B")).findFirst().orElseThrow();
        assertEquals(100, lockB.waitNanos());
        // event [0,100) vs window [100,200): right-open boundary excludes it
        WindowAggregator.Result none = WindowAggregator.query(
                List.of(ev(t0, 0, 100, 1, "A")), t0.plusNanos(100), t0.plusNanos(200));
        assertEquals(0, none.events().size());
        // event starting exactly at 'from' is included (left-closed)
        WindowAggregator.Result edge = WindowAggregator.query(
                List.of(ev(t0, 100, 100, 1, "A")), t0.plusNanos(100), t0.plusNanos(200));
        assertEquals(1, edge.events().size());
    }

    private static ContentionEvent ev(Instant t0, long startNanos, long durNanos,
                                      long tid, String lock) {
        return new ContentionEvent(t0.plusNanos(startNanos), durNanos, tid, lock, List.of());
    }

    @Test
    void corruptAndJsonRecordingsRejected() throws Exception {
        Path dir = Files.createTempDirectory("heapx-jfrbad");
        Path dump = buildThreadDump(dir, 1L, 2L);
        Javalin app = Api.create(new AnalysisService(), 0);
        String base = "http://localhost:" + app.port();
        HttpClient http = HttpClient.newHttpClient();
        try {
            String analysisId = upload(http, base, dump);
            String url = base + "/api/analyses/" + analysisId + "/recordings";
            // corrupt JFR
            HttpResponse<String> bad = http.send(HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{9, 9, 9})).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, bad.statusCode(), bad.body());
            // JSON is not a recording substitute
            HttpResponse<String> json = http.send(HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"events\":[]}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, json.statusCode(), json.body());
            // unknown analysis
            HttpResponse<String> nope = http.send(HttpRequest.newBuilder(
                    URI.create(base + "/api/analyses/nope/recordings"))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1})).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, nope.statusCode());
            http.send(HttpRequest.newBuilder(URI.create(base + "/api/analyses/" + analysisId))
                    .DELETE().build(), HttpResponse.BodyHandlers.ofString());
        } finally {
            app.stop();
        }
    }

    @Test
    void jfrUploadJoinAndWindowQuery() throws Exception {
        Path dir = Files.createTempDirectory("heapx-jfr");
        long[] tids = new long[3];
        Path jfr = recordContention(dir, tids);
        assertTrue(Files.size(jfr) > 0);
        // tidA matched (subclass), tidB ambiguous, tidC unmatched
        Path dump = buildThreadDump(dir, tids[0], tids[1]);

        Javalin app = Api.create(new AnalysisService(), 0);
        String base = "http://localhost:" + app.port();
        HttpClient http = HttpClient.newHttpClient();
        try {
            String analysisId = upload(http, base, dump);

            // upload the real JFR recording
            HttpResponse<String> rec = http.send(HttpRequest.newBuilder(
                    URI.create(base + "/api/analyses/" + analysisId + "/recordings"))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofFile(jfr)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, rec.statusCode(), rec.body());
            String recordingId = rec.body().replaceAll(".*\"recordingId\"\s*:\s*\"([^\"]+)\".*", "$1");
            assertTrue(rec.body().contains("\"events\":"), rec.body());

            // recording summary exposes the thread index
            HttpResponse<String> sum = http.send(HttpRequest.newBuilder(URI.create(
                    base + "/api/analyses/" + analysisId + "/recordings/" + recordingId)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, sum.statusCode(), sum.body());
            assertTrue(sum.body().contains("\"threadObjects\":4"), sum.body());
            assertTrue(sum.body().contains("\"ambiguousTids\":1"), sum.body());
            String firstStart = sum.body().replaceAll(".*\"firstEventStart\":\"([^\"]+)\".*", "$1");
            String lastEnd = sum.body().replaceAll(".*\"lastEventEnd\":\"([^\"]+)\".*", "$1");

            // full-window query: every event intersects
            String q = base + "/api/analyses/" + analysisId + "/recordings/" + recordingId
                    + "/contention?from=" + firstStart + "&to="
                    + Instant.parse(lastEnd).plusNanos(1);
            HttpResponse<String> full = http.send(HttpRequest.newBuilder(URI.create(q)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, full.statusCode(), full.body());
            assertTrue(full.body().contains("\"threadMatch\":\"matched\""), full.body());
            assertTrue(full.body().contains("\"threadMatch\":\"ambiguous\""), full.body());
            assertTrue(full.body().contains("\"threadMatch\":\"unmatched\""), full.body());
            // matched thread carries heap evidence: object id 0x14, retained bytes, root path
            assertTrue(full.body().contains("\"heapObjectId\":\"0x14\""), full.body());
            assertTrue(full.body().contains("\"retainedBytes\":"), full.body());
            assertTrue(full.body().contains("\"rootPath\":{"), full.body());
            assertTrue(full.body().contains("\"pathLength\":0"), full.body()); // thread object is a root
            assertTrue(full.body().contains("\"monitorClass\":\"java.lang.Object\""), full.body());
            assertTrue(full.body().contains("JfrContentionTest"), full.body()); // stack frames recorded

            // narrow window clips events: from = firstStart, to = firstStart+1ns
            String narrowQ = base + "/api/analyses/" + analysisId + "/recordings/" + recordingId
                    + "/contention?from=" + firstStart + "&to="
                    + Instant.parse(firstStart).plusNanos(1);
            HttpResponse<String> narrow = http.send(HttpRequest.newBuilder(URI.create(narrowQ)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, narrow.statusCode(), narrow.body());
            assertTrue(narrow.body().contains("\"clippedNanos\":1"), narrow.body());

            // window validation
            assertEquals(400, get(http, base + "/api/analyses/" + analysisId + "/recordings/"
                    + recordingId + "/contention?to=" + lastEnd).statusCode());
            assertEquals(400, get(http, base + "/api/analyses/" + analysisId + "/recordings/"
                    + recordingId + "/contention?from=" + lastEnd + "&to=" + firstStart).statusCode());
            assertEquals(400, get(http, base + "/api/analyses/" + analysisId + "/recordings/"
                    + recordingId + "/contention?from=not-a-time&to=" + lastEnd).statusCode());

            // delete cleans recordings too
            assertEquals(200, get(http, base + "/api/analyses/" + analysisId, "DELETE").statusCode());
            assertEquals(404, get(http, base + "/api/analyses/" + analysisId + "/recordings/"
                    + recordingId).statusCode());
        } finally {
            app.stop();
        }
    }

    @Test
    void deleteRemovesDumpAndNbCache() throws Exception {
        Path dir = Files.createTempDirectory("heapx-nbcache");
        Path dump = buildThreadDump(dir, 1L, 2L);
        AnalysisService svc = new AnalysisService();
        Analysis a;
        try (var in = Files.newInputStream(dump)) {
            a = svc.analyze(in);
        }
        Path cacheDir = a.source.resolveSibling(a.source.getFileName() + ".nbcache");
        assertTrue(Files.exists(a.source));
        assertTrue(Files.isDirectory(cacheDir), "NetBeans reader cache expected next to the dump");
        assertTrue(svc.delete(a.id));
        assertFalse(Files.exists(a.source));
        assertFalse(Files.exists(cacheDir), ".nbcache residue must be removed on delete");
    }

    @Test
    void corruptJfrPublishesNothing() throws Exception {
        AnalysisService svc = new AnalysisService();
        // unknown analysis -> null, nothing published
        assertNull(svc.addRecording("missing",
                new java.io.ByteArrayInputStream(new byte[]{1, 2, 3})));
        // corrupt JFR on a real analysis -> 400 AnalysisException, no recording
        Path dir = Files.createTempDirectory("heapx-jfrcorrupt");
        Path dump = buildThreadDump(dir, 1L, 2L);
        Analysis a;
        try (var in = Files.newInputStream(dump)) {
            a = svc.analyze(in);
        }
        AnalysisException ex = assertThrows(AnalysisException.class, () -> svc.addRecording(a.id,
                new java.io.ByteArrayInputStream(new byte[]{1, 2, 3})));
        assertEquals(400, ex.status);
        assertNull(svc.getRecording(a.id, "anything"));
        svc.delete(a.id);
    }

    private static String upload(HttpClient http, String base, Path dump) throws Exception {
        HttpResponse<String> up = http.send(HttpRequest.newBuilder(URI.create(base + "/api/analyses"))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofFile(dump)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, up.statusCode(), up.body());
        return up.body().replaceAll(".*\"analysisId\"\s*:\s*\"([^\"]+)\".*", "$1");
    }

    private static HttpResponse<String> get(HttpClient http, String url) throws Exception {
        return get(http, url, "GET");
    }

    private static HttpResponse<String> get(HttpClient http, String url, String method) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).method(method,
                HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
}
