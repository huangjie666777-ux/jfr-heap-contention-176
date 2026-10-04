package heapx.jfr;

import java.time.Instant;
import java.util.List;

/**
 * One jdk.JavaMonitorEnter event: the interval a Java thread waited to
 * enter a contended monitor. Only start/end, the Java thread id, the
 * monitor class and the stack are kept; every other event type and
 * field is ignored at parse time.
 */
public record ContentionEvent(Instant start, long durationNanos, long javaThreadId,
                              String monitorClass, List<String> stack) {
    public Instant end() { return start.plusNanos(durationNanos); }
}
