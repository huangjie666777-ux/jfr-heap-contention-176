package heapx.jfr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Half-open [from, to) window queries over contention events. Events are
 * clipped to the window first, then merged: overlapping intervals of the
 * same thread (or same thread+lock class) are never double counted.
 */
public final class ContentionQuery {

    /** An event clipped to the query window; clipEnd is exclusive. */
    public record ClippedEvent(ContentionEvent event, long clipStart, long clipEnd) {
        public long durationNanos() { return clipEnd - clipStart; }
    }

    /** Keeps events intersecting [from, to), clipped to the window. */
    public static List<ClippedEvent> clip(List<ContentionEvent> events, long from, long to) {
        List<ClippedEvent> out = new ArrayList<>();
        for (ContentionEvent e : events) {
            long s = Math.max(e.startNanos(), from);
            long en = Math.min(e.endNanos(), to);
            if (s < en) out.add(new ClippedEvent(e, s, en));
        }
        return out;
    }

    /** Union length in nanos of half-open [start, end) intervals. */
    public static long unionNanos(List<long[]> intervals) {
        if (intervals.isEmpty()) return 0;
        List<long[]> sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator.comparingLong(iv -> iv[0]));
        long total = 0;
        long curStart = sorted.get(0)[0];
        long curEnd = sorted.get(0)[1];
        for (int i = 1; i < sorted.size(); i++) {
            long s = sorted.get(i)[0];
            long e = sorted.get(i)[1];
            if (s > curEnd) {
                total += curEnd - curStart;
                curStart = s;
                curEnd = e;
            } else if (e > curEnd) {
                curEnd = e;
            }
        }
        return total + (curEnd - curStart);
    }
}
