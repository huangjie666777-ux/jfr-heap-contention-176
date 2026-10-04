package heapx.service;

import heapx.jfr.ContentionEvent;

import java.nio.file.Path;
import java.util.List;

/** One completed, immutable JFR contention recording bound to an analysis.
 *  Published atomically by id; the source file is retained until the
 *  owning analysis is deleted. */
public final class Recording {
    public final String id;
    public final String analysisId;
    public final List<ContentionEvent> events;
    public final Path source;
    public final long minStartNanos;   // Long.MAX_VALUE when no events
    public final long maxEndNanos;     // Long.MIN_VALUE when no events

    public Recording(String id, String analysisId, List<ContentionEvent> events, Path source) {
        this.id = id;
        this.analysisId = analysisId;
        this.events = events;
        this.source = source;
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (ContentionEvent e : events) {
            min = Math.min(min, e.startNanos());
            max = Math.max(max, e.endNanos());
        }
        this.minStartNanos = min;
        this.maxEndNanos = max;
    }
}
