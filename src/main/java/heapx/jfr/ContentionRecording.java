package heapx.jfr;

import java.time.Instant;
import java.util.List;

/**
 * One published JFR lock-contention recording bound to an analysis.
 * Immutable; events are sorted by start time. Only jdk.JavaMonitorEnter
 * events are kept. Reflects recorded contention only: it never asserts
 * deadlocks or leaks, and it is not a snapshot simultaneous with the
 * heap dump (the two were captured at different times).
 */
public final class ContentionRecording {
    public final String recordingId;
    public final String analysisId;
    public final List<ContentionEvent> events;
    public final Instant firstStart;   // null when no events
    public final Instant lastEnd;      // null when no events
    public final long createdAtMillis;

    public ContentionRecording(String recordingId, String analysisId, List<ContentionEvent> events) {
        this.recordingId = recordingId;
        this.analysisId = analysisId;
        this.events = List.copyOf(events);
        Instant first = null, last = null;
        for (ContentionEvent e : events) {
            if (first == null || e.start().isBefore(first)) first = e.start();
            if (last == null || e.end().isAfter(last)) last = e.end();
        }
        this.firstStart = first;
        this.lastEnd = last;
        this.createdAtMillis = System.currentTimeMillis();
    }
}
