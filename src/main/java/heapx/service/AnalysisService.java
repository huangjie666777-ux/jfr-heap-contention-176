package heapx.service;

import heapx.graph.DominatorAnalysis;
import heapx.jfr.ContentionEvent;
import heapx.jfr.JfrParser;
import heapx.mask.HprofArrays;
import heapx.mask.MaskExporter;
import heapx.mask.ScanResult;
import heapx.mask.SensitiveScanner;
import heapx.model.HeapModel;
import heapx.parse.AnalysisException;
import heapx.parse.HprofParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Parses uploads, runs dominator analysis and hands out independent
 * analysis ids. Failed or over-limit uploads publish nothing. Concurrent
 * uploads/queries/scans are isolated; delete frees the source dump, all
 * scans and every other resource. No persistence.
 */
public final class AnalysisService {
    public static final long MAX_BYTES = 100L * 1024 * 1024;
    public static final long MAX_JFR_BYTES = 100L * 1024 * 1024;
    public static final long MAX_OBJECTS =
            Long.parseLong(System.getenv().getOrDefault("HEAPX_MAX_OBJECTS", "50000"));
    public static final long MAX_EDGES =
            Long.parseLong(System.getenv().getOrDefault("HEAPX_MAX_EDGES", "200000"));

    private static final class TooLargeException extends RuntimeException {}

    private final Map<String, Analysis> analyses = new ConcurrentHashMap<>();
    private final Map<String, ScanResult> scans = new ConcurrentHashMap<>();
    private final Map<String, Recording> recordings = new ConcurrentHashMap<>();
    private final HprofParser parser = new HprofParser(MAX_OBJECTS, MAX_EDGES);

    public Analysis analyze(InputStream in) throws AnalysisException {
        Path tmp = null;
        boolean published = false;
        try {
            tmp = Files.createTempFile("heapx-upload-", ".hprof");
            copyBounded(in, tmp);
            HeapModel model = parser.parse(tmp.toFile());
            DominatorAnalysis da = DominatorAnalysis.compute(model);
            Analysis a = new Analysis(UUID.randomUUID().toString(), model, da, tmp);
            analyses.put(a.id, a);
            published = true;
            return a;
        } catch (TooLargeException e) {
            throw new AnalysisException(422,
                    "file limit exceeded: upload is larger than " + MAX_BYTES + " bytes (100 MiB)");
        } catch (IOException e) {
            throw new AnalysisException(400, "failed to store upload: " + e.getMessage());
        } finally {
            if (!published && tmp != null) {
                deleteDumpArtifacts(tmp);
            }
        }
    }

    private static void copyBounded(InputStream in, Path target) throws IOException {
        copyBounded(in, target, MAX_BYTES);
    }

    private static void copyBounded(InputStream in, Path target, long maxBytes) throws IOException {
        long total = 0;
        byte[] buf = new byte[1 << 16];
        try (var out = Files.newOutputStream(target)) {
            int r;
            while ((r = in.read(buf)) != -1) {
                total += r;
                if (total > maxBytes) throw new TooLargeException();
                out.write(buf, 0, r);
            }
        }
    }

    /** Parses a JFR upload for the given analysis and keeps only
     *  jdk.JavaMonitorEnter contention events. Failed or over-limit
     *  uploads publish nothing. */
    public Recording addRecording(String analysisId, InputStream in) throws AnalysisException {
        Analysis a = analyses.get(analysisId);
        if (a == null) return null;
        Path tmp = null;
        boolean published = false;
        try {
            tmp = Files.createTempFile("heapx-jfr-", ".jfr");
            copyBounded(in, tmp, MAX_JFR_BYTES);
            List<ContentionEvent> events = JfrParser.parse(tmp);
            Recording r = new Recording(UUID.randomUUID().toString(), analysisId, events, tmp);
            recordings.put(r.id, r);
            published = true;
            return r;
        } catch (TooLargeException e) {
            throw new AnalysisException(422,
                    "file limit exceeded: JFR upload is larger than " + MAX_JFR_BYTES + " bytes (100 MiB)");
        } catch (IOException e) {
            throw new AnalysisException(400, "failed to store upload: " + e.getMessage());
        } finally {
            if (!published && tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }

    public Recording getRecording(String analysisId, String recordingId) {
        Recording r = recordings.get(recordingId);
        return r != null && r.analysisId.equals(analysisId) ? r : null;
    }

    /** Runs a sensitive-value scan over the retained source dump. The scan
     *  is built fully before publication; failures publish nothing. */
    public ScanResult createScan(String analysisId, List<SensitiveScanner.RuleInput> inputs)
            throws AnalysisException {
        Analysis a = analyses.get(analysisId);
        if (a == null) return null;
        List<SensitiveScanner.Rule> rules = SensitiveScanner.validate(inputs);
        byte[] file = readSource(a);
        List<HprofArrays.ArrayPayload> arrays = HprofArrays.locate(file);
        ScanResult scan = SensitiveScanner.scan(UUID.randomUUID().toString(), analysisId,
                file, arrays, rules, a.model, a.dominators);
        scans.put(scan.scanId, scan);
        return scan;
    }

    public ScanResult getScan(String analysisId, String scanId) {
        ScanResult s = scans.get(scanId);
        return s != null && s.analysisId.equals(analysisId) ? s : null;
    }

    /** Builds the masked HPROF copy for a completed scan, in memory;
     *  the original dump is never modified. */
    public MaskExporter.Masked exportMasked(String analysisId, String scanId)
            throws AnalysisException {
        Analysis a = analyses.get(analysisId);
        if (a == null) return null;
        ScanResult scan = getScan(analysisId, scanId);
        if (scan == null) return null;
        return MaskExporter.apply(readSource(a), scan);
    }

    private static byte[] readSource(Analysis a) throws AnalysisException {
        try {
            return Files.readAllBytes(a.source);
        } catch (IOException e) {
            throw new AnalysisException(500, "failed to read retained dump: " + e.getMessage());
        }
    }

    public Analysis get(String id) { return analyses.get(id); }

    public boolean delete(String id) {
        Analysis a = analyses.remove(id);
        if (a == null) return false;
        scans.values().removeIf(s -> s.analysisId.equals(id));
        List<Recording> removed = recordings.values().stream()
                .filter(r -> r.analysisId.equals(id)).toList();
        recordings.values().removeIf(r -> r.analysisId.equals(id));
        for (Recording r : removed) {
            try { Files.deleteIfExists(r.source); } catch (IOException ignored) {}
        }
        deleteDumpArtifacts(a.source);
        return true;
    }

    /** Removes the retained dump plus the NetBeans reader cache directory
     *  ({<file>.nbcache}) it may leave next to the dump. */
    static void deleteDumpArtifacts(Path source) {
        try { Files.deleteIfExists(source); } catch (IOException ignored) {}
        Path cache = source.resolveSibling(source.getFileName() + ".nbcache");
        if (!Files.exists(cache)) return;
        try (Stream<Path> walk = Files.walk(cache)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    public int count() { return analyses.size(); }
}
