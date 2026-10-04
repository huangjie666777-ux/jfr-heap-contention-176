package heapx.service;

import heapx.graph.DominatorAnalysis;
import heapx.model.HeapModel;
import heapx.model.ThreadIndex;

import java.nio.file.Path;

/** One completed, immutable heap analysis. Published atomically by id.
 *  The original dump file is retained for sensitive-value scans and
 *  masked exports until the analysis is deleted. */
public final class Analysis {
    public final String id;
    public final HeapModel model;
    public final DominatorAnalysis dominators;
    public final ThreadIndex threads;
    public final Path source;
    public final long createdAtMillis;

    public Analysis(String id, HeapModel model, DominatorAnalysis dominators,
                    ThreadIndex threads, Path source) {
        this.id = id;
        this.model = model;
        this.dominators = dominators;
        this.threads = threads;
        this.source = source;
        this.createdAtMillis = System.currentTimeMillis();
    }
}
