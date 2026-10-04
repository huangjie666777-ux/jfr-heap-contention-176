package heapx.parse;

import heapx.model.HeapModel;
import heapx.model.ThreadIndex;
import org.netbeans.lib.profiler.heap.Field;
import org.netbeans.lib.profiler.heap.FieldValue;
import org.netbeans.lib.profiler.heap.GCRoot;
import org.netbeans.lib.profiler.heap.Heap;
import org.netbeans.lib.profiler.heap.HeapFactory;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.JavaClass;
import org.netbeans.lib.profiler.heap.ObjectArrayInstance;
import org.netbeans.lib.profiler.heap.ObjectFieldValue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a HotSpot HPROF file (4- or 8-byte identifiers, segmented dumps
 * supported by the NetBeans profiler reader) and extracts objects, shallow
 * sizes and strong-reference edges. Retained sizes are NOT taken from the
 * library; they are computed later by heapx.graph.DominatorAnalysis.
 */
public final class HprofParser {
    private final long maxObjects;
    private final long maxEdges;

    /** Heap model plus the java.lang.Thread tid index used for JFR joins. */
    public record ParseResult(HeapModel model, ThreadIndex threads) {}

    public HprofParser(long maxObjects, long maxEdges) {
        this.maxObjects = maxObjects;
        this.maxEdges = maxEdges;
    }

    public HeapModel parse(File dump) throws AnalysisException {
        return parseFull(dump).model();
    }

    public ParseResult parseFull(File dump) throws AnalysisException {
        Heap heap;
        try {
            heap = HeapFactory.createHeap(dump);
        } catch (IOException | RuntimeException e) {
            throw new AnalysisException(400, "corrupt or unsupported HPROF file: " + e.getMessage());
        }
        if (heap == null) {
            throw new AnalysisException(400, "corrupt or unsupported HPROF file");
        }
        try {
            return build(heap);
        } catch (AnalysisException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AnalysisException(400, "failed to read HPROF: " + e.getMessage());
        }
    }

    private ParseResult build(Heap heap) throws AnalysisException {
        List<Instance> instances = new ArrayList<>();
        for (Object cls : heap.getAllClasses()) {
            instances.addAll(((JavaClass) cls).getInstances());
        }
        int n = instances.size();
        if (n > maxObjects) {
            throw new AnalysisException(422,
                    "object limit exceeded: " + n + " > " + maxObjects);
        }

        // NB: the reader hands out fresh Instance wrappers per call, so key by id.
        Map<Long, Integer> index = new HashMap<>(n * 2);
        long[] ids = new long[n];
        String[] classNames = new String[n];
        long[] shallow = new long[n];
        boolean[] root = new boolean[n];
        for (int i = 0; i < n; i++) {
            Instance in = instances.get(i);
            index.put(in.getInstanceId(), i);
            ids[i] = in.getInstanceId();
            classNames[i] = in.getJavaClass().getName();
            shallow[i] = in.getSize();
        }

        // GC roots provided by the dump (thread stacks, JNI, monitors, ...)
        for (Object rootObj : heap.getGCRoots()) {
            Instance in = ((GCRoot) rootObj).getInstance();
            if (in != null) {
                Integer idx = index.get(in.getInstanceId());
                if (idx != null) root[idx] = true;
            }
        }
        // Class static reference targets are roots too.
        for (Object cls : heap.getAllClasses()) {
            for (Object sfv : ((JavaClass) cls).getStaticFieldValues()) {
                FieldValue fv = (FieldValue) sfv;
                if (fv instanceof ObjectFieldValue ofv) {
                    Instance target = ofv.getInstance();
                    if (target != null) {
                        Integer idx = index.get(target.getInstanceId());
                        if (idx != null) root[idx] = true;
                    }
                }
            }
        }

        // Edges from instance fields (inherited included) and array elements.
        List<int[]> edgePairs = new ArrayList<>();   // [from, to]
        List<String> edgeLabels = new ArrayList<>();
        long edgeCount = 0;
        for (int from = 0; from < n; from++) {
            Instance in = instances.get(from);
            if (in instanceof ObjectArrayInstance arr) {
                List<Instance> values = arr.getValues();
                for (int i = 0; i < values.size(); i++) {
                    Instance target = values.get(i);
                    if (target == null) continue; // ignore null elements
                    Integer to = index.get(target.getInstanceId());
                    if (to == null) continue;
                    edgePairs.add(new int[]{from, to});
                    edgeLabels.add("[" + i + "]");
                    if (++edgeCount > maxEdges) throw edgeLimit(edgeCount);
                }
            } else {
                for (Object fvObj : in.getFieldValues()) {
                    if (!(fvObj instanceof ObjectFieldValue ofv)) continue;
                    Field f = ofv.getField();
                    // exclude java.lang.ref.Reference.referent (weak/soft/phantom)
                    if ("referent".equals(f.getName())
                            && "java.lang.ref.Reference".equals(f.getDeclaringClass().getName())) {
                        continue;
                    }
                    Instance target = ofv.getInstance();
                    if (target == null) continue; // ignore null fields
                    Integer to = index.get(target.getInstanceId());
                    if (to == null) continue;
                    edgePairs.add(new int[]{from, to});
                    edgeLabels.add(f.getDeclaringClass().getName() + "#" + f.getName());
                    if (++edgeCount > maxEdges) throw edgeLimit(edgeCount);
                }
            }
        }

        int m = edgePairs.size();
        int[] outStart = new int[n + 1];
        int[] outTo = new int[m];
        String[] outLabel = new String[m];
        int[] inStart = new int[n + 1];
        int[] inFrom = new int[m];
        String[] inLabel = new String[m];
        for (int[] p : edgePairs) { outStart[p[0] + 1]++; inStart[p[1] + 1]++; }
        for (int i = 0; i < n; i++) { outStart[i + 1] += outStart[i]; inStart[i + 1] += inStart[i]; }
        int[] outCursor = outStart.clone();
        int[] inCursor = inStart.clone();
        for (int e = 0; e < m; e++) {
            int from = edgePairs.get(e)[0];
            int to = edgePairs.get(e)[1];
            String label = edgeLabels.get(e);
            outTo[outCursor[from]] = to;
            outLabel[outCursor[from]++] = label;
            inFrom[inCursor[to]] = from;
            inLabel[inCursor[to]++] = label;
        }
        HeapModel model = new HeapModel(ids, classNames, shallow, root,
                outStart, outTo, outLabel, inStart, inFrom, inLabel);
        return new ParseResult(model, extractThreads(heap, index));
    }

    /**
     * Reads the long field {@code tid} declared by java.lang.Thread from
     * every Thread instance (subclasses included). A tid declared by more
     * than one thread object is ambiguous and matches nothing; a thread
     * object whose dump record lacks the field is counted as missing.
     * Names, OS ids and monitor addresses are never used.
     */
    private static ThreadIndex extractThreads(Heap heap, Map<Long, Integer> index) {
        Map<JavaClass, Boolean> isThread = new HashMap<>();
        Map<Long, Long> unique = new HashMap<>();
        Set<Long> ambiguous = new HashSet<>();
        int threadObjects = 0;
        int missingTid = 0;
        for (Object clsObj : heap.getAllClasses()) {
            JavaClass cls = (JavaClass) clsObj;
            if (!isThreadClass(cls, isThread)) continue;
            for (Object instObj : cls.getInstances()) {
                Instance in = (Instance) instObj;
                threadObjects++;
                Long tid = readTid(in);
                if (tid == null) { missingTid++; continue; }
                if (ambiguous.contains(tid)) continue;
                Long prev = unique.put(tid, in.getInstanceId());
                if (prev != null) {
                    unique.remove(tid);
                    ambiguous.add(tid);
                }
            }
        }
        return new ThreadIndex(unique, ambiguous, threadObjects, missingTid);
    }

    private static boolean isThreadClass(JavaClass cls, Map<JavaClass, Boolean> cache) {
        Boolean cached = cache.get(cls);
        if (cached != null) return cached;
        boolean result = false;
        for (JavaClass c = cls; c != null; c = c.getSuperClass()) {
            if ("java.lang.Thread".equals(c.getName())) { result = true; break; }
        }
        cache.put(cls, result);
        return result;
    }

    private static Long readTid(Instance in) {
        for (Object fvObj : in.getFieldValues()) {
            FieldValue fv = (FieldValue) fvObj;
            Field f = fv.getField();
            if (!"tid".equals(f.getName())) continue;
            if (!"java.lang.Thread".equals(f.getDeclaringClass().getName())) continue;
            if (!"long".equals(f.getType().getName())) continue;
            try {
                return Long.parseLong(fv.getValue().trim());
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private AnalysisException edgeLimit(long count) {
        return new AnalysisException(422, "edge limit exceeded: " + count + " > " + maxEdges);
    }
}
