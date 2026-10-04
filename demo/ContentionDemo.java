import java.util.*;

/** Real JVM demo: worker threads contend on a shared monitor while the
 *  heap holds a cache of byte arrays, so the same JVM yields a genuine
 *  JFR recording (jdk.JavaMonitorEnter) and an HPROF dump. */
public class ContentionDemo {
    static final Object LOCK = new Object();
    static final List<byte[]> cache = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 30; i++) cache.add(new byte[64 * 1024]);
        Thread[] workers = new Thread[4];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new Thread(() -> {
                for (int k = 0; k < 40; k++) {
                    try { Thread.sleep(2); } catch (InterruptedException e) { return; }
                    synchronized (LOCK) {
                        try { Thread.sleep(6); } catch (InterruptedException e) { return; }
                    }
                }
            }, "contender-" + i);
            workers[i].start();
        }
        for (Thread t : workers) t.join();
        System.out.println("contention done; tids: " + Arrays.toString(
                Arrays.stream(workers).mapToLong(Thread::getId).toArray()));
        Thread.sleep(120_000); // stay alive for jmap / jcmd
    }
}
