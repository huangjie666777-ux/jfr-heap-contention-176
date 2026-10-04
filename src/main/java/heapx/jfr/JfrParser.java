package heapx.jfr;

import heapx.parse.AnalysisException;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a real JFR recording with jdk.jfr.consumer and extracts
 * jdk.JavaMonitorEnter events only. Everything else (other event types,
 * allocations, parks, waits) is ignored. Corrupt or non-JFR input is
 * rejected with 400 and publishes nothing.
 */
public final class JfrParser {
    public static final int MAX_EVENTS = 100_000;
    private static final int MAX_STACK_FRAMES = 64;
    private static final String EVENT = "jdk.JavaMonitorEnter";

    private JfrParser() {}

    /** @return contention events sorted by start time */
    public static List<ContentionEvent> parse(Path file) throws AnalysisException {
        List<ContentionEvent> events = new ArrayList<>();
        try (RecordingFile rf = new RecordingFile(file)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                if (e == null || !EVENT.equals(e.getEventType().getName())) continue;
                RecordedThread thread = e.getThread();
                if (thread == null) continue; // cannot join to the heap without a Java thread id
                long tid = thread.getJavaThreadId();
                String monitorClass = e.getClass("monitorClass") == null
                        ? "<unknown>" : e.getClass("monitorClass").getName();
                events.add(new ContentionEvent(e.getStartTime(),
                        e.getDuration().toNanos(), tid, monitorClass, stackOf(e)));
                if (events.size() > MAX_EVENTS) {
                    throw new AnalysisException(422, "contention event limit exceeded: more than "
                            + MAX_EVENTS + " jdk.JavaMonitorEnter events");
                }
            }
        } catch (AnalysisException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new AnalysisException(400, "corrupt or unsupported JFR file: " + ex.getMessage());
        }
        events.sort((a, b) -> a.start().compareTo(b.start()));
        return events;
    }

    private static List<String> stackOf(RecordedEvent e) {
        RecordedStackTrace st = e.getStackTrace();
        if (st == null) return List.of();
        List<String> frames = new ArrayList<>();
        for (RecordedFrame f : st.getFrames()) {
            if (frames.size() >= MAX_STACK_FRAMES) break;
            if (f.getMethod() == null) continue;
            frames.add(f.getMethod().getType().getName() + "." + f.getMethod().getName()
                    + ":" + f.getLineNumber());
        }
        return List.copyOf(frames);
    }
}
