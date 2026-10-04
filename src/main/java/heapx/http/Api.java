package heapx.http;

import heapx.graph.PathFinder;
import heapx.graph.CutPlanner;
import heapx.jfr.ContentionEvent;
import heapx.jfr.ContentionRecording;
import heapx.jfr.WindowAggregator;
import heapx.mask.MaskExporter;
import heapx.mask.ScanResult;
import heapx.mask.SensitiveScanner;
import heapx.model.HeapModel;
import heapx.model.ThreadIndex;
import heapx.parse.AnalysisException;
import heapx.service.Analysis;
import heapx.service.AnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Javalin routes for upload, retained ranking, root paths, cut plans and delete. */
public final class Api {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(Api.class);
    static final int MAX_TARGETS = 32;
    static final int MAX_CANDIDATES = 1000;
    static final long MAX_COST = 1_000_000_000L;

    public static Javalin create(AnalysisService service, int port) {
        Javalin app = Javalin.create(cfg -> {
            cfg.http.maxRequestSize = AnalysisService.MAX_BYTES + (1 << 20);
            cfg.showJavalinBanner = false;
        });

        app.post("/api/analyses", ctx -> {
            try (InputStream in = bodyOf(ctx)) {
                if (in == null) {
                    ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error",
                            "provide the HPROF file as multipart field 'file' or raw request body"));
                    return;
                }
                Analysis a = service.analyze(in);
                ctx.status(HttpStatus.CREATED).json(summary(a));
            } catch (AnalysisException e) {
                ctx.status(e.status).json(Map.of("error", e.getMessage()));
            }
        });

        app.get("/api/analyses/{id}", ctx -> {
            Analysis a = require(ctx, service);
            if (a != null) ctx.json(summary(a));
        });

        app.get("/api/analyses/{id}/retained", ctx -> {
            Analysis a = require(ctx, service);
            if (a == null) return;
            int limit = Math.min(intParam(ctx, "limit", 50), 1000);
            int offset = Math.max(intParam(ctx, "offset", 0), 0);
            HeapModel g = a.model;
            int n = g.nodeCount();
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            // unreachable objects are excluded from the retained ranking
            var da = a.dominators;
            List<Integer> reachable = new ArrayList<>(n);
            for (int i = 0; i < n; i++) if (!da.unreachable[i]) reachable.add(i);
            reachable.sort((x, y) -> Long.compare(da.retained[y], da.retained[x]));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = offset; i < Math.min(offset + limit, reachable.size()); i++) {
                rows.add(objectRow(a, reachable.get(i)));
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("analysisId", a.id);
            resp.put("rankedObjects", reachable.size());
            resp.put("unreachableObjects", n - reachable.size());
            resp.put("objects", rows);
            ctx.json(resp);
        });

        app.get("/api/analyses/{id}/objects/{hexId}", ctx -> {
            Analysis a = require(ctx, service);
            if (a == null) return;
            Integer idx = objectIndex(ctx, a);
            if (idx == null) return;
            ctx.json(objectRow(a, idx));
        });

        app.get("/api/analyses/{id}/objects/{hexId}/path", ctx -> {
            Analysis a = require(ctx, service);
            if (a == null) return;
            Integer idx = objectIndex(ctx, a);
            if (idx == null) return;
            HeapModel g = a.model;
            if (a.dominators.unreachable[idx]) {
                ctx.status(HttpStatus.forStatus(422)).json(Map.of(
                        "error", "object is unreachable from GC roots",
                        "objectId", hex(g.ids[idx])));
                return;
            }
            List<PathFinder.Step> steps = PathFinder.shortestPathFromRoot(g, a.dominators, idx);
            if (steps == null) {
                ctx.status(HttpStatus.forStatus(422))
                        .json(Map.of("error", "no strong-reference path from any root"));
                return;
            }
            List<Map<String, Object>> edges = new ArrayList<>();
            for (PathFinder.Step s : steps) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("from", hex(s.fromId()));
                e.put("fromClass", s.fromClass());
                e.put("via", s.via());
                e.put("to", hex(s.toId()));
                e.put("toClass", s.toClass());
                edges.add(e);
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("analysisId", a.id);
            resp.put("objectId", hex(g.ids[idx]));
            resp.put("rootObjectId", steps.isEmpty() ? hex(g.ids[idx]) : hex(steps.get(0).fromId()));
            resp.put("isRoot", g.root[idx]);
            resp.put("pathLength", steps.size());
            resp.put("edges", edges);
            ctx.json(resp);
        });

        app.delete("/api/analyses/{id}", ctx -> {
            if (service.delete(ctx.pathParam("id"))) {
                ctx.json(Map.of("deleted", ctx.pathParam("id")));
            } else {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "unknown analysis id"));
            }
        });

        app.post("/api/analyses/{id}/cut-plan", ctx -> {
            Analysis a = require(ctx, service);
            if (a == null) return;
            JsonNode body;
            try {
                body = JSON.readTree(ctx.body());
            } catch (Exception e) {
                badRequest(ctx, "invalid JSON body");
                return;
            }
            if (body == null || !body.isObject()) {
                badRequest(ctx, "JSON object with 'targets' and 'candidates' expected");
                return;
            }
            int[] targets = parseTargets(ctx, a, body.get("targets"));
            if (targets == null) return;
            List<CutPlanner.Candidate> candidates = parseCandidates(ctx, a, body.get("candidates"));
            if (candidates == null) return;

            HeapModel g = a.model;
            CutPlanner.Result res = CutPlanner.plan(g, targets, candidates);
            if (!res.feasible()) {
                Map<String, Object> evidence = new LinkedHashMap<>();
                int t = res.evidenceTarget();
                evidence.put("target", hex(g.ids[t]));
                evidence.put("targetIsRoot", g.root[t]);
                List<Map<String, Object>> path = new ArrayList<>();
                for (PathFinder.Step s : res.evidence()) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("from", hex(s.fromId()));
                    e.put("fromClass", s.fromClass());
                    e.put("via", s.via());
                    e.put("to", hex(s.toId()));
                    e.put("toClass", s.toClass());
                    path.add(e);
                }
                evidence.put("uncuttablePath", path);
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("analysisId", a.id);
                resp.put("feasible", false);
                resp.put("error", g.root[t]
                        ? "target is a GC root / static-reference target and cannot be disconnected"
                        : "target stays reachable through references that are not in the cuttable list");
                resp.put("evidence", evidence);
                ctx.status(HttpStatus.forStatus(422)).json(resp);
                return;
            }

            boolean[] removed = new boolean[g.edgeCount()];
            List<Map<String, Object>> cuts = new ArrayList<>();
            for (CutPlanner.Candidate c : res.cuts()) {
                removed[c.edgeIndex()] = true;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("source", hex(g.ids[c.from()]));
                e.put("via", c.label());
                e.put("target", hex(g.ids[c.to()]));
                e.put("cost", c.cost());
                cuts.add(e);
            }
            // newly unreachable = root-reachable set difference before/after the cut
            boolean[] before = CutPlanner.reachableFrom(g, null);
            boolean[] after = CutPlanner.reachableFrom(g, removed);
            List<String> freedIds = new ArrayList<>();
            long freedBytes = 0;
            for (int i = 0; i < g.nodeCount(); i++) {
                if (before[i] && !after[i]) {
                    freedIds.add(hex(g.ids[i]));
                    freedBytes += g.shallow[i];
                }
            }
            List<String> alreadyDead = new ArrayList<>();
            for (int t : targets) {
                if (a.dominators.unreachable[t]) alreadyDead.add(hex(g.ids[t]));
            }
            Map<String, Object> freed = new LinkedHashMap<>();
            freed.put("count", freedIds.size());
            freed.put("shallowBytes", freedBytes);
            freed.put("ids", freedIds);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("analysisId", a.id);
            resp.put("feasible", true);
            resp.put("totalCost", res.totalCost());
            resp.put("cuts", cuts);
            resp.put("newlyUnreachable", freed);
            resp.put("alreadyUnreachableTargets", alreadyDead);
            ctx.json(resp);
        });


        app.post("/api/analyses/{id}/scans", ctx -> {
            Analysis a = require(ctx, service);
            if (a == null) return;
            JsonNode body;
            try {
                body = JSON.readTree(ctx.body());
            } catch (Exception e) {
                badRequest(ctx, "invalid JSON body");
                return;
            }
            if (body == null || !body.isObject() || body.get("rules") == null
                    || !body.get("rules").isArray()) {
                badRequest(ctx, "JSON object with a 'rules' array of {id, text} expected");
                return;
            }
            List<SensitiveScanner.RuleInput> inputs = new ArrayList<>();
            for (JsonNode r : body.get("rules")) {
                JsonNode id = r == null ? null : r.get("id");
                JsonNode text = r == null ? null : r.get("text");
                inputs.add(new SensitiveScanner.RuleInput(
                        id != null && id.isTextual() ? id.asText() : null,
                        text != null && text.isTextual() ? text.asText() : null));
            }
            try {
                ScanResult scan = service.createScan(a.id, inputs);
                LOG.info("scan {} created for analysis {}: {} rules, {} hits",
                        scan.scanId, a.id, scan.ruleCount, scan.hitCount());
                ctx.status(HttpStatus.CREATED).json(scanJson(scan));
            } catch (AnalysisException e) {
                ctx.status(e.status).json(Map.of("error", e.getMessage()));
            }
        });

        app.get("/api/analyses/{id}/scans/{scanId}", ctx -> {
            Analysis a = require(ctx, service);
            if (a == null) return;
            ScanResult scan = requireScan(ctx, service, a);
            if (scan != null) ctx.json(scanJson(scan));
        });

        app.get("/api/analyses/{id}/scans/{scanId}/export", ctx -> {
            Analysis a = require(ctx, service);
            if (a == null) return;
            ScanResult scan = requireScan(ctx, service, a);
            if (scan == null) return;
            MaskExporter.Masked masked;
            try {
                masked = service.exportMasked(a.id, scan.scanId);
            } catch (AnalysisException e) {
                ctx.status(e.status).json(Map.of("error", e.getMessage()));
                return;
            }
            LOG.info("scan {} exported for analysis {}: {} arrays masked, {} bytes zeroed",
                    scan.scanId, a.id, masked.arraysModified(), masked.bytesMasked());
            ctx.header("X-Masked-Arrays", String.valueOf(masked.arraysModified()));
            ctx.header("X-Masked-Bytes", String.valueOf(masked.bytesMasked()));
            ctx.header("Content-Disposition",
                    "attachment; filename=\"masked-" + scan.scanId + ".hprof\"");
            ctx.contentType("application/octet-stream");
            ctx.result(masked.data());
        });

        app.post("/api/analyses/{id}/recordings", ctx -> {
            Analysis a = require(ctx, service);
            if (a == null) return;
            String ct = ctx.contentType();
            if (ct != null && ct.toLowerCase().startsWith("application/json")) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error",
                        "a real JFR recording file is required (multipart field 'file' or raw "
                                + "request body); JSON metadata is not accepted as a substitute"));
                return;
            }
            try (InputStream in = bodyOf(ctx)) {
                if (in == null) {
                    ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error",
                            "provide the JFR file as multipart field 'file' or raw request body"));
                    return;
                }
                ContentionRecording rec = service.addRecording(a.id, in);
                LOG.info("recording {} published for analysis {}: {} contention events",
                        rec.recordingId, a.id, rec.events.size());
                ctx.status(HttpStatus.CREATED).json(recordingJson(a, rec));
            } catch (AnalysisException e) {
                ctx.status(e.status).json(Map.of("error", e.getMessage()));
            }
        });

        app.get("/api/analyses/{id}/recordings/{recordingId}", ctx -> {
            Analysis a = require(ctx, service);
            if (a == null) return;
            ContentionRecording rec = requireRecording(ctx, service, a);
            if (rec != null) ctx.json(recordingJson(a, rec));
        });

        app.get("/api/analyses/{id}/recordings/{recordingId}/contention", ctx -> {
            Analysis a = require(ctx, service);
            if (a == null) return;
            ContentionRecording rec = requireRecording(ctx, service, a);
            if (rec == null) return;
            Instant from = instantParam(ctx, "from");
            if (from == null) return;
            Instant to = instantParam(ctx, "to");
            if (to == null) return;
            if (!from.isBefore(to)) {
                badRequest(ctx, "invalid window: 'from' must be strictly before 'to' "
                        + "(left-closed, right-open)");
                return;
            }
            int eventLimit = Math.min(intParam(ctx, "eventLimit", 1000), 100_000);
            WindowAggregator.Result res = WindowAggregator.query(rec.events, from, to);
            ctx.json(contentionJson(a, res, eventLimit));
        });

        app.start(port);
        return app;
    }

    private static ContentionRecording requireRecording(Context ctx, AnalysisService service,
                                                        Analysis a) {
        ContentionRecording rec = service.getRecording(a.id, ctx.pathParam("recordingId"));
        if (rec == null) {
            ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "unknown recording id"));
        }
        return rec;
    }

    private static Instant instantParam(Context ctx, String name) {
        String v = ctx.queryParam(name);
        if (v == null) {
            badRequest(ctx, "missing query parameter '" + name
                    + "' (ISO-8601 UTC, e.g. 2026-10-04T14:00:00Z)");
            return null;
        }
        try {
            return Instant.parse(v);
        } catch (RuntimeException e) {
            badRequest(ctx, "invalid '" + name + "': expected ISO-8601 UTC instant, got " + v);
            return null;
        }
    }

    private static Map<String, Object> recordingJson(Analysis a, ContentionRecording rec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("analysisId", a.id);
        m.put("recordingId", rec.recordingId);
        m.put("events", rec.events.size());
        m.put("firstEventStart", rec.firstStart == null ? null : rec.firstStart.toString());
        m.put("lastEventEnd", rec.lastEnd == null ? null : rec.lastEnd.toString());
        Map<String, Object> threads = new LinkedHashMap<>();
        threads.put("threadObjects", a.threads.threadObjects);
        threads.put("uniqueTids", a.threads.tidToObjectId.size());
        threads.put("ambiguousTids", a.threads.ambiguousTids.size());
        threads.put("missingTid", a.threads.missingTid);
        m.put("threadIndex", threads);
        m.put("note", "recorded contention only; the JFR and the heap dump were captured "
                + "at different times and are not simultaneous snapshots");
        return m;
    }

    private static Map<String, Object> contentionJson(Analysis a, WindowAggregator.Result res,
                                                      int eventLimit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("analysisId", a.id);
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("from", res.from().toString());
        window.put("to", res.to().toString());
        window.put("semantics", "left-closed, right-open; events clipped to the window, "
                + "overlaps of the same thread merged after clipping");
        m.put("window", window);
        m.put("intersectingEvents", res.events().size());

        List<Map<String, Object>> events = new ArrayList<>();
        int shown = 0;
        for (WindowAggregator.ClippedEvent ce : res.events()) {
            if (shown++ >= eventLimit) break;
            ContentionEvent e = ce.event();
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("start", e.start().toString());
            ev.put("end", e.end().toString());
            ev.put("durationNanos", e.durationNanos());
            ev.put("clippedStart", ce.start().toString());
            ev.put("clippedEnd", ce.end().toString());
            ev.put("clippedNanos", ce.nanos());
            ev.put("javaThreadId", e.javaThreadId());
            ev.put("monitorClass", e.monitorClass());
            ev.put("threadMatch", a.threads.statusOf(e.javaThreadId())
                    .name().toLowerCase());
            ev.put("stack", e.stack());
            events.add(ev);
        }
        m.put("events", events);
        m.put("eventsTruncated", res.events().size() > events.size());

        List<Map<String, Object>> threads = new ArrayList<>();
        for (WindowAggregator.ThreadAgg t : res.threads()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("javaThreadId", t.javaThreadId());
            row.put("events", t.events());
            row.put("waitNanos", t.waitNanos());
            List<Map<String, Object>> locks = new ArrayList<>();
            for (WindowAggregator.LockAgg l : t.locks()) {
                Map<String, Object> lk = new LinkedHashMap<>();
                lk.put("monitorClass", l.monitorClass());
                lk.put("events", l.events());
                lk.put("waitNanos", l.waitNanos());
                locks.add(lk);
            }
            row.put("locks", locks);
            attachHeapEvidence(a, t.javaThreadId(), row);
            threads.add(row);
        }
        m.put("threads", threads);
        return m;
    }

    /** Joins a JFR Java thread id to the heap via the HPROF Thread.tid
     *  index only (never names, OS ids or monitor addresses). */
    private static void attachHeapEvidence(Analysis a, long tid, Map<String, Object> row) {
        ThreadIndex.Status status = a.threads.statusOf(tid);
        row.put("threadMatch", status.name().toLowerCase());
        if (status != ThreadIndex.Status.MATCHED) {
            row.put("heapObjectId", null);
            return;
        }
        long objectId = a.threads.objectIdOf(tid);
        Integer idx = a.model.indexOf(objectId);
        row.put("heapObjectId", hex(objectId));
        if (idx == null) return;
        HeapModel g = a.model;
        row.put("unreachable", a.dominators.unreachable[idx]);
        if (a.dominators.unreachable[idx]) {
            row.put("retainedBytes", null);
            row.put("rootPath", null);
            return;
        }
        row.put("retainedBytes", a.dominators.retained[idx]);
        List<PathFinder.Step> steps = PathFinder.shortestPathFromRoot(g, a.dominators, idx);
        if (steps == null) {
            row.put("rootPath", null);
            return;
        }
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("rootObjectId", steps.isEmpty() ? hex(g.ids[idx]) : hex(steps.get(0).fromId()));
        path.put("pathLength", steps.size());
        path.put("edges", edgesJson(steps));
        row.put("rootPath", path);
    }


    private static ScanResult requireScan(Context ctx, AnalysisService service, Analysis a) {
        ScanResult scan = service.getScan(a.id, ctx.pathParam("scanId"));
        if (scan == null) {
            ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "unknown scan id"));
        }
        return scan;
    }

    private static Map<String, Object> scanJson(ScanResult scan) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("analysisId", scan.analysisId);
        resp.put("scanId", scan.scanId);
        resp.put("ruleCount", scan.ruleCount);
        resp.put("hitCount", scan.hitCount());
        List<Map<String, Object>> hits = new ArrayList<>();
        for (ScanResult.Hit h : scan.hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ruleId", h.ruleId());
            m.put("arrayId", hex(h.arrayId()));
            m.put("arrayType", h.arrayType());
            m.put("elementStart", h.elementStart());
            m.put("length", h.length());
            m.put("reachable", h.reachable());
            if (h.reachable() && h.path() != null) {
                Map<String, Object> path = new LinkedHashMap<>();
                path.put("rootObjectId", h.path().isEmpty()
                        ? hex(h.arrayId()) : hex(h.path().get(0).fromId()));
                path.put("pathLength", h.path().size());
                path.put("edges", edgesJson(h.path()));
                m.put("path", path);
            }
            hits.add(m);
        }
        resp.put("hits", hits);
        return resp;
    }

    private static List<Map<String, Object>> edgesJson(List<PathFinder.Step> steps) {
        List<Map<String, Object>> edges = new ArrayList<>();
        for (PathFinder.Step s : steps) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("from", hex(s.fromId()));
            e.put("fromClass", s.fromClass());
            e.put("via", s.via());
            e.put("to", hex(s.toId()));
            e.put("toClass", s.toClass());
            edges.add(e);
        }
        return edges;
    }

    private static int[] parseTargets(Context ctx, Analysis a, JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            badRequest(ctx, "targets must be a non-empty array of hex object ids");
            return null;
        }
        if (node.size() > MAX_TARGETS) {
            badRequest(ctx, "target limit exceeded: " + node.size() + " > " + MAX_TARGETS);
            return null;
        }
        HeapModel g = a.model;
        int[] targets = new int[node.size()];
        Set<Integer> seen = new HashSet<>();
        int count = 0;
        for (JsonNode t : node) {
            if (!t.isTextual()) {
                badRequest(ctx, "each target must be a hex object id string");
                return null;
            }
            long id;
            try {
                id = parseHex(t.asText());
            } catch (NumberFormatException e) {
                badRequest(ctx, "invalid hex object id: " + t.asText());
                return null;
            }
            Integer idx = g.indexOf(id);
            if (idx == null) {
                badRequest(ctx, "unknown target object id: " + t.asText());
                return null;
            }
            if (seen.add(idx)) targets[count++] = idx;
        }
        return count == targets.length ? targets : java.util.Arrays.copyOf(targets, count);
    }

    private static List<CutPlanner.Candidate> parseCandidates(Context ctx, Analysis a, JsonNode node) {
        if (node == null || !node.isArray()) {
            badRequest(ctx, "candidates must be an array of {source, via, target, cost}");
            return null;
        }
        if (node.size() > MAX_CANDIDATES) {
            badRequest(ctx, "candidate limit exceeded: " + node.size() + " > " + MAX_CANDIDATES);
            return null;
        }
        HeapModel g = a.model;
        List<CutPlanner.Candidate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode c : node) {
            JsonNode source = c.get("source");
            JsonNode via = c.get("via");
            JsonNode target = c.get("target");
            JsonNode cost = c.get("cost");
            if (source == null || !source.isTextual() || via == null || !via.isTextual()
                    || target == null || !target.isTextual() || cost == null) {
                badRequest(ctx, "each candidate needs string source/via/target and integer cost");
                return null;
            }
            if (!cost.isIntegralNumber() || !cost.canConvertToLong()
                    || cost.longValue() < 1 || cost.longValue() > MAX_COST) {
                badRequest(ctx, "cost must be an integer in [1, " + MAX_COST + "]: " + cost);
                return null;
            }
            Integer from = lookup(ctx, g, source.asText());
            if (from == null) return null;
            Integer to = lookup(ctx, g, target.asText());
            if (to == null) return null;
            String key = from + "|" + via.asText() + "|" + to;
            if (!seen.add(key)) {
                badRequest(ctx, "duplicate candidate: " + source.asText()
                        + " --" + via.asText() + "--> " + target.asText());
                return null;
            }
            int edge = findEdge(g, from, to, via.asText());
            if (edge < 0) {
                badRequest(ctx, "no such reference: " + source.asText()
                        + " --" + via.asText() + "--> " + target.asText());
                return null;
            }
            candidates.add(new CutPlanner.Candidate(from, to, via.asText(), cost.longValue(), edge));
        }
        return candidates;
    }

    private static Integer lookup(Context ctx, HeapModel g, String hexId) {
        long id;
        try {
            id = parseHex(hexId);
        } catch (NumberFormatException e) {
            badRequest(ctx, "invalid hex object id: " + hexId);
            return null;
        }
        Integer idx = g.indexOf(id);
        if (idx == null) badRequest(ctx, "unknown object id: " + hexId);
        return idx;
    }

    private static int findEdge(HeapModel g, int from, int to, String label) {
        for (int e = g.outStart[from]; e < g.outStart[from + 1]; e++) {
            if (g.outTo[e] == to && g.outLabel[e].equals(label)) return e;
        }
        return -1;
    }

    private static void badRequest(Context ctx, String message) {
        ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", message));
    }

    private static InputStream bodyOf(Context ctx) throws Exception {
        var uploaded = ctx.uploadedFile("file");
        if (uploaded != null) return uploaded.content();
        if (ctx.contentLength() > 0 || ctx.contentLength() == -1) return ctx.bodyInputStream();
        return null;
    }

    private static Map<String, Object> summary(Analysis a) {
        HeapModel g = a.model;
        int unreachable = 0;
        for (boolean u : a.dominators.unreachable) if (u) unreachable++;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("analysisId", a.id);
        m.put("objects", g.nodeCount());
        m.put("edges", g.edgeCount());
        m.put("unreachableObjects", unreachable);
        return m;
    }

    private static Map<String, Object> objectRow(Analysis a, int idx) {
        HeapModel g = a.model;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", hex(g.ids[idx]));
        m.put("className", g.classNames[idx]);
        m.put("shallowBytes", g.shallow[idx]);
        m.put("root", g.root[idx]);
        m.put("unreachable", a.dominators.unreachable[idx]);
        if (a.dominators.unreachable[idx]) {
            m.put("retainedBytes", null);
            m.put("immediateDominator", null);
        } else {
            m.put("retainedBytes", a.dominators.retained[idx]);
            int p = a.dominators.idom[idx];
            m.put("immediateDominator",
                    p == a.dominators.virtualRoot ? "<virtual-root>" : hex(g.ids[p]));
        }
        return m;
    }

    private static Analysis require(Context ctx, AnalysisService service) {
        Analysis a = service.get(ctx.pathParam("id"));
        if (a == null) {
            ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "unknown analysis id"));
        }
        return a;
    }

    private static Integer objectIndex(Context ctx, Analysis a) {
        long id;
        try {
            id = parseHex(ctx.pathParam("hexId"));
        } catch (NumberFormatException e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "invalid hex object id"));
            return null;
        }
        Integer idx = a.model.indexOf(id);
        if (idx == null) {
            ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "unknown object id"));
        }
        return idx;
    }

    static String hex(long id) { return "0x" + Long.toHexString(id); }

    static long parseHex(String s) {
        String t = s.startsWith("0x") || s.startsWith("0X") ? s.substring(2) : s;
        return Long.parseUnsignedLong(t, 16);
    }

    private static int intParam(Context ctx, String name, int dflt) {
        String v = ctx.queryParam(name);
        if (v == null) return dflt;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return dflt; }
    }
}
