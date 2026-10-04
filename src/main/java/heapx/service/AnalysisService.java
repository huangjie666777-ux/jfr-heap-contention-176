package heapx.service;

import heapx.graph.DominatorAnalysis;
import heapx.jfr.ContentionRecording;
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
    public static final long MAX_OBJECTS =
            Long.parseLong(System.getenv().getOrDefault("HEAPX_MAX_OBJECTS", "50000"));
    public static final long MAX_EDGES =
            Long.parseLong(System.getenv().getOrDefault("HEAPX_MAX_EDGES", "200000"));

    private static final class TooLargeException extends RuntimeException {}

    private final Map<String, Analysis> analyses = new ConcurrentHashMap<>();
    private final Map<String, ScanResult> scans = new ConcurrentHashMap<>();
    private final Map<String, ContentionRecording> recordings = new ConcurrentHashMap<>();
    private final HprofParser parser = new HprofParser(MAX_OBJECTS, MAX_EDGES);

    public Analysis analyze(InputStream in) throws AnalysisException {
        Path tmp = null;
        boolean published = false;
        try {
            tmp = Files.createTempFile("heapx-upload-", ".hprof");
            copyBounded(in, tmp);
            HprofParser.ParseResult parsed = parser.parseFull(tmp.toFile());
            HeapModel model = parsed.model();
            DominatorAnalysis da = DominatorAnalysis.compute(model);
            Analysis a = new Analysis(UUID.randomUUID().toString(), model, da,
                    parsed.threads(), tmp);
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
                cleanupDump(tmp);
            }
        }
    }

    private static void copyBounded(InputStream in, Path target) throws IOException {
        long total = 0;
        byte[] buf = new byte[1 << 16];
        try (var out = Files.newOutputStream(target)) {
            int r;
            while ((r = in.read(buf)) != -1) {
                total += r;
                if (total > MAX_BYTES) throw new TooLargeException();
                out.write(buf, 0, r);
            }
        }
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

    /**
     * Parses an uploaded JFR recording (real file only, never JSON) and
     * publishes it bound to the analysis. Corrupt or over-limit uploads
     * publish nothing; the temporary file is always removed.
     */
    public ContentionRecording addRecording(String analysisId, InputStream in)
            throws AnalysisException {
        Analysis a = analyses.get(analysisId);
        if (a == null) return null;
        Path tmp = null;
        boolean published = false;
        try {
            tmp = Files.createTempFile("heapx-jfr-", ".jfr");
            copyBounded(in, tmp);
            var events = JfrParser.parse(tmp);
            ContentionRecording rec = new ContentionRecording(
                    UUID.randomUUID().toString(), analysisId, events);
            recordings.put(rec.recordingId, rec);
            published = true;
            return rec;
        } catch (TooLargeException e) {
            throw new AnalysisException(422,
                    "file limit exceeded: JFR upload is larger than " + MAX_BYTES + " bytes (100 MiB)");
        } catch (IOException e) {
            throw new AnalysisException(400, "failed to store JFR upload: " + e.getMessage());
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }

    public ContentionRecording getRecording(String analysisId, String recordingId) {
        ContentionRecording r = recordings.get(recordingId);
        return r != null && r.analysisId.equals(analysisId) ? r : null;
    }

    public boolean delete(String id) {
        Analysis a = analyses.remove(id);
        if (a == null) return false;
        scans.values().removeIf(s -> s.analysisId.equals(id));
        recordings.values().removeIf(r -> r.analysisId.equals(id));
        cleanupDump(a.source);
        return true;
    }

    /** Removes the retained dump plus the NetBeans reader cache directory
     *  ({@code <dump>.nbcache}) that HeapFactory leaves next to the file. */
    private static void cleanupDump(Path dump) {
        try { Files.deleteIfExists(dump); } catch (IOException ignored) {}
        Path cache = dump.resolveSibling(dump.getFileName() + ".nbcache");
        if (!Files.exists(cache)) return;
        try (Stream<Path> walk = Files.walk(cache)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    public int count() { return analyses.size(); }
}
