package heapx.model;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Index of java.lang.Thread (and subclass) instances by the long field
 * {@code tid} declared in java.lang.Thread, read from the HPROF dump.
 * This is the only join key accepted for JFR Java thread ids: never the
 * thread name, OS id or monitor address. A tid seen on more than one
 * thread object is ambiguous and matches nothing; a thread object whose
 * dump record lacks the tid field is counted as missing.
 */
public final class ThreadIndex {
    public enum Status { MATCHED, AMBIGUOUS, UNMATCHED }

    public final Map<Long, Long> tidToObjectId;   // unique tid -> HPROF object id
    public final Set<Long> ambiguousTids;         // tids declared by 2+ thread objects
    public final int threadObjects;               // Thread/subclass instances seen
    public final int missingTid;                  // thread objects without a readable tid

    public ThreadIndex(Map<Long, Long> unique, Set<Long> ambiguous,
                       int threadObjects, int missingTid) {
        this.tidToObjectId = Collections.unmodifiableMap(unique);
        this.ambiguousTids = Collections.unmodifiableSet(ambiguous);
        this.threadObjects = threadObjects;
        this.missingTid = missingTid;
    }

    public Status statusOf(long tid) {
        if (tidToObjectId.containsKey(tid)) return Status.MATCHED;
        if (ambiguousTids.contains(tid)) return Status.AMBIGUOUS;
        return Status.UNMATCHED;
    }

    /** @return HPROF object id of the unique thread object for tid, else null */
    public Long objectIdOf(long tid) { return tidToObjectId.get(tid); }
}
