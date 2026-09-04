package com.company.agentgateway.interfaces.replay;

import com.company.agentgateway.application.replay.ReplayService;
import com.company.agentgateway.domain.observability.SpanQueryRepository;
import com.company.agentgateway.domain.replay.PayloadCapturePort;
import com.company.agentgateway.domain.replay.ReplayRequest;
import com.company.agentgateway.domain.replay.ReplayResult;
import com.company.agentgateway.domain.replay.TraceDiffService;
import com.company.agentgateway.domain.replay.TraceDiffService.DiffResult;
import com.company.agentgateway.domain.replay.TraceDiffService.TraceSnapshot;
import com.company.agentgateway.domain.replay.TraceDiffService.SpanView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Replay 管理端点(Sprint 2 P0 §3.4):
 * <ul>
 *   <li>POST /v1/admin/traces/{traceId}/replay — 单条同步重放</li>
 *   <li>POST /v1/admin/traces/{traceId}/replay/batch — 批量变体</li>
 *   <li>POST /v1/admin/traces/replay/load — 压测(并发 + 持续时长)</li>
 *   <li>GET  /v1/admin/traces/{traceId}/diff?against=… — 与另一条 trace 对比</li>
 *   <li>GET  /v1/admin/replay/jobs?jobId=… — 查 job</li>
 *   <li>GET  /v1/admin/replay/jobs/recent?limit=20 — 最近 N 条</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminReplayController {

    private final ReplayService replayService;
    private final PayloadCapturePort payloadPort;
    private SpanQueryRepository spanQuery;  // 可选 setter 注入(Sprint 2 P0 + Round 8 修复:
                                             // 避免 SpanQueryRepository bean 缺失时
                                             // controller 装配失败 — setSpanQuery
                                             // 在 bean 生命周期后调用)

    @Autowired  // Spring 4.0 严格模式:显式标记主构造器
    public AdminReplayController(ReplayService replayService, PayloadCapturePort payloadPort) {
        this.replayService = replayService;
        this.payloadPort = payloadPort;
    }

    @Autowired(required = false)  // 可选 setter:无 SpanQueryRepository bean 时不影响装配
    public void setSpanQuery(SpanQueryRepository spanQuery) {
        this.spanQuery = spanQuery;
    }

    @PostMapping("/traces/{traceId}/replay")
    public ReplayResult replay(@PathVariable String traceId,
                                @RequestBody(required = false) ReplayRequest body,
                                @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        ReplayRequest req = body != null ? body : new ReplayRequest(traceId,
                ReplayRequest.ReplayOverrides.empty(), true, false, null, null);
        // 强制使用路径上的 traceId
        if (!req.traceId().equals(traceId)) {
            req = new ReplayRequest(traceId, req.overrides(), req.safeReplay(),
                    req.allowMutatingTools(), req.callbackUrl(), req.metadata());
        }
        return replayService.replay(req, apiKey, null);
    }

    @PostMapping("/traces/{traceId}/replay/batch")
    public List<ReplayResult> replayBatch(@PathVariable String traceId,
                                            @RequestBody BatchRequest body,
                                            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        List<ReplayResult> out = new ArrayList<>();
        if (body.variants() == null) return out;
        for (var variant : body.variants()) {
            ReplayRequest req = new ReplayRequest(traceId, variant,
                    body.safeReplay() == null ? true : body.safeReplay(),
                    body.allowMutatingTools() == null ? false : body.allowMutatingTools(),
                    null, null);
            out.add(replayService.replay(req, apiKey, null));
        }
        return out;
    }

    @PostMapping("/traces/replay/load")
    public Map<String, Object> loadReplay(@RequestBody LoadRequest body,
                                           @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        // Sprint 2 P0 简化为:串行执行 + 报告(真实并发池留 P1)
        List<ReplayResult> results = new ArrayList<>();
        if (body.traceIds() == null) return Map.of("results", results, "count", 0);
        for (String tid : body.traceIds()) {
            ReplayRequest req = new ReplayRequest(tid,
                    ReplayRequest.ReplayOverrides.empty(), true, false, null, null);
            results.add(replayService.replay(req, apiKey, null));
        }
        return Map.of("results", results, "count", results.size());
    }

    @GetMapping("/traces/{traceId}/diff")
    public DiffResult diff(@PathVariable String traceId,
                            @RequestParam("against") String otherTraceId) {
        TraceSnapshot a = snapshotOf(traceId);
        TraceSnapshot b = snapshotOf(otherTraceId);
        return TraceDiffService.diff(a, b);
    }

    @GetMapping("/replay/jobs")
    public ReplayResult job(@RequestParam String jobId) {
        ReplayResult r = replayService.job(jobId);
        return r != null ? r : new ReplayResult(jobId, null, null,
                ReplayResult.Status.FAILED, null, null,
                ReplayResult.Kind.DEFAULT, false, 0, Map.of(), "job not found");
    }

    @GetMapping("/replay/jobs/recent")
    public List<ReplayResult> recentJobs(@RequestParam(defaultValue = "20") int limit) {
        return replayService.recentJobs(limit);
    }

    // ─── 内部 ───

    /** 从 payload 表组装 TraceSnapshot(简化为从 RESPONSE body 提取 model/tokens/response)。 */
    private TraceSnapshot snapshotOf(String traceId) {
        var req = payloadPort.findByTraceAndRole(traceId, PayloadCapturePort.Role.REQUEST).orElse(null);
        var resp = payloadPort.findByTraceAndRole(traceId, PayloadCapturePort.Role.RESPONSE).orElse(null);

        // Sprint 2 P4:payload 缺失时从 span attributes 反向合成
        if (req == null && resp == null && spanQuery != null) {
            try {
                var spans = spanQuery.getSpans(traceId);
                if (spans != null && !spans.isEmpty()) {
                    var views = new java.util.ArrayList<SpanView>();
                    for (var s : spans) {
                        java.util.Map<String, String> attrs = s.attributes() == null
                                ? java.util.Map.of() : s.attributes();
                        Long st = s.startTime() == null ? null : s.startTime().toEpochMilli();
                        Long et = s.endTime() == null ? null : s.endTime().toEpochMilli();
                        views.add(new SpanView(s.name(), st, et, attrs));
                    }
                    return TraceSnapshot.fromSpans(traceId, views);
                }
            } catch (RuntimeException ignored) { /* fall through */ }
            return new TraceSnapshot(traceId, null, null, null, 0L, 0, List.of(), "");
        }

        String model = extractField(req, "model", null);
        Integer tokensIn = extractInt(resp, "tokens_in");
        Integer tokensOut = extractInt(resp, "tokens_out");
        String responseText = resp == null ? "" : extractAfterDoubleNewline(resp.body());
        return new TraceSnapshot(traceId, model, tokensIn, tokensOut, 0L, 1, List.of(), responseText);
    }

    private static String extractField(PayloadCapturePort.PayloadRecord r, String key, String dft) {
        if (r == null) return dft;
        try {
            int i = r.body().indexOf("\"" + key + "\"");
            if (i < 0) return dft;
            int s = r.body().indexOf(':', i);
            int e = r.body().indexOf(',', s);
            if (e < 0) e = r.body().indexOf('}', s);
            String v = r.body().substring(s + 1, e).trim();
            return v.startsWith("\"") && v.endsWith("\"") ? v.substring(1, v.length() - 1) : v;
        } catch (Exception e) { return dft; }
    }

    private static Integer extractInt(PayloadCapturePort.PayloadRecord r, String key) {
        if (r == null) return null;
        try {
            int i = r.body().indexOf("\"" + key + "\"");
            if (i < 0) return null;
            int s = r.body().indexOf(':', i);
            int e = r.body().indexOf(',', s);
            if (e < 0) e = r.body().indexOf('}', s);
            return Integer.parseInt(r.body().substring(s + 1, e).trim());
        } catch (Exception e) { return null; }
    }

    private static String extractAfterDoubleNewline(String body) {
        int idx = body.indexOf("\n\n");
        return idx < 0 ? "" : body.substring(idx + 2);
    }

    /** 批量请求体:多个 ReplayOverrides。 */
    public record BatchRequest(List<ReplayRequest.ReplayOverrides> variants,
                                Boolean safeReplay,
                                Boolean allowMutatingTools) {}

    /** 压测请求体:traceIds + 并发 + 持续时长(Sprint 2 P0 仅 traceIds,串行执行)。 */
    public record LoadRequest(List<String> traceIds, Integer concurrency, String duration) {}
}