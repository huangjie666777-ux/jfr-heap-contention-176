package heapx.jfr;

import java.util.List;

/**
 * One jdk.JavaMonitorEnter event: the interval a Java thread waited to
 * enter a contended monitor. Times are UTC epoch nanoseconds; end is
 * exclusive for window intersection purposes. The stack is the recorded
 * top frames ("declaring.Class.method(File.java:line)"), top-first.
 */
public record ContentionEvent(long startNanos, long endNanos, long javaThreadId,
                              String monitorClass, List<String> stack) {}
