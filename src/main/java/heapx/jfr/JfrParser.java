package heapx.jfr;

import heapx.parse.AnalysisException;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a real JFR recording with jdk.jfr.consumer and keeps only
 * jdk.JavaMonitorEnter events (monitor contention); every other event
 * type is ignored. At most MAX_EVENTS contention events are accepted.
 */
public final class JfrParser {
    public static final int MAX_EVENTS = 100_000;
    private static final int MAX_STACK_FRAMES = 128;

    public static List<ContentionEvent> parse(Path file) throws AnalysisException {
        List<ContentionEvent> events = new ArrayList<>();
        try (RecordingFile rf = new RecordingFile(file)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                if (e == null) break;
                if (!"jdk.JavaMonitorEnter".equals(e.getEventType().getName())) continue;
                RecordedThread thread = e.getThread();
                long javaThreadId = thread != null ? thread.getJavaThreadId() : -1;
                RecordedClass monitor = e.getClass("monitorClass");
                String monitorClass = monitor != null ? monitor.getName() : "<unknown>";
                events.add(new ContentionEvent(
                        toEpochNanos(e.getStartTime()), toEpochNanos(e.getEndTime()),
                        javaThreadId, monitorClass, stackOf(e)));
                if (events.size() > MAX_EVENTS) {
                    throw new AnalysisException(422, "contention event limit exceeded: more than "
                            + MAX_EVENTS + " jdk.JavaMonitorEnter events");
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new AnalysisException(400, "corrupt or unsupported JFR file: " + e.getMessage());
        }
        return events;
    }

    private static List<String> stackOf(RecordedEvent e) {
        RecordedStackTrace st = e.getStackTrace();
        if (st == null) return List.of();
        List<String> frames = new ArrayList<>();
        for (RecordedFrame f : st.getFrames()) {
            if (frames.size() >= MAX_STACK_FRAMES) break;
            RecordedMethod m = f.getMethod();
            if (m == null) continue;
            frames.add(m.getType().getName() + "." + m.getName()
                    + "(" + f.getLineNumber() + ")");
        }
        return frames;
    }

    public static long toEpochNanos(Instant i) {
        return Math.addExact(Math.multiplyExact(i.getEpochSecond(), 1_000_000_000L), i.getNano());
    }
}
