package heapx.jfr;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Windowed aggregation over contention events. The window is
 * left-closed/right-open [from, to): an event contributes when it
 * intersects the window, and only its clipped part counts. Clipping
 * happens BEFORE interval merging, so overlapping waits of the same
 * thread are never double counted. Per-thread and per-lock-class
 * durations are union (merged-interval) nanoseconds, not sums.
 */
public final class WindowAggregator {

    public record ClippedEvent(ContentionEvent event, Instant start, Instant end, long nanos) {}

    public record LockAgg(String monitorClass, int events, long waitNanos) {}

    public record ThreadAgg(long javaThreadId, int events, long waitNanos, List<LockAgg> locks) {}

    public record Result(Instant from, Instant to, List<ClippedEvent> events,
                         List<ThreadAgg> threads) {}

    public static Result query(List<ContentionEvent> events, Instant from, Instant to) {
        List<ClippedEvent> clipped = new ArrayList<>();
        Map<Long, List<long[]>> byThread = new LinkedHashMap<>();
        Map<Long, Map<String, List<long[]>>> byThreadLock = new LinkedHashMap<>();
        Map<Long, Integer> counts = new LinkedHashMap<>();
        Map<Long, Map<String, Integer>> lockCounts = new LinkedHashMap<>();

        for (ContentionEvent e : events) {
            Instant es = e.start();
            Instant ee = e.end();
            if (!es.isBefore(to) || !ee.isAfter(from)) continue; // no intersection with [from, to)
            Instant cs = es.isBefore(from) ? from : es;
            Instant ce = ee.isAfter(to) ? to : ee;
            long nanos = java.time.Duration.between(cs, ce).toNanos();
            if (nanos <= 0) continue;
            clipped.add(new ClippedEvent(e, cs, ce, nanos));
            long tid = e.javaThreadId();
            byThread.computeIfAbsent(tid, k -> new ArrayList<>())
                    .add(new long[]{toNanos(cs), toNanos(ce)});
            byThreadLock.computeIfAbsent(tid, k -> new LinkedHashMap<>())
                    .computeIfAbsent(e.monitorClass(), k -> new ArrayList<>())
                    .add(new long[]{toNanos(cs), toNanos(ce)});
            counts.merge(tid, 1, Integer::sum);
            lockCounts.computeIfAbsent(tid, k -> new LinkedHashMap<>())
                    .merge(e.monitorClass(), 1, Integer::sum);
        }

        List<ThreadAgg> threads = new ArrayList<>();
        for (var entry : byThread.entrySet()) {
            long tid = entry.getKey();
            List<LockAgg> locks = new ArrayList<>();
            for (var le : byThreadLock.get(tid).entrySet()) {
                locks.add(new LockAgg(le.getKey(),
                        lockCounts.get(tid).get(le.getKey()), unionNanos(le.getValue())));
            }
            locks.sort(Comparator.comparingLong(LockAgg::waitNanos).reversed());
            threads.add(new ThreadAgg(tid, counts.get(tid), unionNanos(entry.getValue()), locks));
        }
        threads.sort(Comparator.comparingLong(ThreadAgg::waitNanos).reversed());
        return new Result(from, to, clipped, threads);
    }

    /** Union length of half-open [start, end) intervals in nanoseconds. */
    static long unionNanos(List<long[]> intervals) {
        intervals.sort(Comparator.comparingLong(iv -> iv[0]));
        long total = 0, curS = 0, curE = -1;
        for (long[] iv : intervals) {
            if (curE < 0) { curS = iv[0]; curE = iv[1]; continue; }
            if (iv[0] <= curE) {
                if (iv[1] > curE) curE = iv[1];
            } else {
                total += curE - curS;
                curS = iv[0];
                curE = iv[1];
            }
        }
        if (curE >= 0) total += curE - curS;
        return total;
    }

    private static long toNanos(Instant t) {
        return Math.addExact(Math.multiplyExact(t.getEpochSecond(), 1_000_000_000L), t.getNano());
    }
}
