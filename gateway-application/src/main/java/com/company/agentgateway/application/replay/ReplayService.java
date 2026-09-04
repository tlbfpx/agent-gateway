package com.company.agentgateway.application.replay;

import com.company.agentgateway.application.orchestration.ChatOrchestrator;
import com.company.agentgateway.application.orchestration.ChatRequest;
import com.company.agentgateway.application.orchestration.ChatStreamEvent;
import com.company.agentgateway.domain.replay.PayloadCapturePort;
import com.company.agentgateway.domain.replay.ReplayRequest;
import com.company.agentgateway.domain.replay.ReplayResult;
import com.company.agentgateway.domain.shared.SessionId;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replay 服务(Sprint 2 P0):把历史 trace 重放 + 可选覆盖部分参数。
 *
 * <h2>关键路径</h2>
 * <ol>
 *   <li>从 PayloadCapturePort 还原原请求的 messages / model / tools</li>
 *   <li>应用 ReplayOverrides(覆盖字段优先;null 字段用原值)</li>
 *   <li>调用 ChatOrchestrator 走同一编排链路(产生新 traceId)</li>
 *   <li>Safe Mode:跳过 mutating 工具(Sprint 2 P0 简化为完全跳过所有 tool)</li>
 *   <li>返回新 traceId + 状态(同步模式);callbackUrl 模式另算</li>
 * </ol>
 *
 * <h2>同步契约</h2>
 * <p>Sprint 2 P0 仅同步模式;批量 / 异步 / 压测留作 Sprint 2 P1。
 */
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    private final PayloadCapturePort payloadPort;
    private final ChatOrchestrator orchestrator;
    private final ObjectMapper objectMapper;
    private final Map<String, ReplayResult> jobs = new ConcurrentHashMap<>();
    /** Sprint 2 P5 + P2.3:有界队列 + 背压的异步执行器(替代裸线程池)。 */
    private final ReplayAsyncExecutor asyncExecutor = new ReplayAsyncExecutor();
    /** 可选:callback URL POST 客户端(Sprint 2 P5)。 */
    private final java.util.function.BiConsumer<String, ReplayResult> callbackPoster =
            (url, result) -> postCallback(url, result);
    /** Sprint 2 P2.2:可选 callback 签名器(注入 null = 不签)。 */
    private final CallbackSigner callbackSigner;

    public ReplayService(PayloadCapturePort payloadPort, ChatOrchestrator orchestrator,
                        ObjectMapper objectMapper) {
        this(payloadPort, orchestrator, objectMapper, null);
    }

    public ReplayService(PayloadCapturePort payloadPort, ChatOrchestrator orchestrator,
                        ObjectMapper objectMapper, CallbackSigner callbackSigner) {
        this.payloadPort = payloadPort;
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
        this.callbackSigner = callbackSigner;
    }

    /**
     * 同步重放。
     *
     * @return 含新 traceId + replayedFrom + safeReplay + 收集的完整文本
     */
    public ReplayResult replay(ReplayRequest req, String apiKey, String tenantIdHeader) {
        String jobId = UUID.randomUUID().toString();
        ReplayResult pending = ReplayResult.pending(jobId, req.traceId(), ReplayResult.Kind.DEFAULT, req.safeReplay());
        jobs.put(jobId, pending);

        // Sprint 2 P5:异步路径 — 立刻返回 PENDING,后台跑;callbackUrl 非空时完成后 POST
        if (req.callbackUrl() != null && !req.callbackUrl().isBlank()) {
            asyncExecutor.submit(() -> {
                ReplayResult result = runSync(req, apiKey, tenantIdHeader, pending, jobId);
                jobs.put(jobId, result);
                callbackPoster.accept(req.callbackUrl(), result);
            });
            return pending;
        }

        return runSync(req, apiKey, tenantIdHeader, pending, jobId);
    }

    /** 同步执行实际 replay(供异步路径复用)。 */
    private ReplayResult runSync(ReplayRequest req, String apiKey, String tenantIdHeader,
                                  ReplayResult pending, String jobId) {

        // 1. 还原原 payload
        PayloadCapturePort.PayloadRecord reqPayload =
                payloadPort.findByTraceAndRole(req.traceId(), PayloadCapturePort.Role.REQUEST)
                        .orElseThrow(() -> new IllegalStateException(
                                "No request payload captured for trace=" + req.traceId()
                                        + "; payload retention may have expired, or capture is not enabled"));
        PayloadCapturePort.PayloadRecord respPayload =
                payloadPort.findByTraceAndRole(req.traceId(), PayloadCapturePort.Role.RESPONSE)
                        .orElse(null);

        // 2. 解析原 messages / model
        OriginalRequest original = parseOriginal(reqPayload.body());
        ChatRequest replay = buildChatRequest(original, req, jobId);

        // 3. 调 ChatOrchestrator;新 traceId 由 SpanContext 派生(Sprint 2 P0:用 jobId 替代)
        log.info("replay start: jobId={} traceId={} safeReplay={}", jobId, req.traceId(), req.safeReplay());
        StringBuilder collected = new StringBuilder();
        Flux<ChatStreamEvent> stream = orchestrator.orchestrate(replay, apiKey, tenantIdHeader);
        List<ChatStreamEvent> events = new ArrayList<>();
        // 同步收集(Sprint 2 P0:沿用 orchestrator 已有 sink 模式)
        try {
            stream.toStream().forEach(ev -> {
                events.add(ev);
                if (ev instanceof ChatStreamEvent.Delta d) collected.append(d.content());
            });
        } catch (Exception e) {
            log.error("replay {} failed: {}", jobId, e.getMessage());
            ReplayResult failed = withStatus(pending, ReplayResult.Status.FAILED, e.getMessage());
            jobs.put(jobId, failed);
            return failed;
        }
        return finalizeSuccess(pending, jobId, respPayload, replay, collected.toString());
    }

    private ReplayResult finalizeSuccess(ReplayResult pending, String jobId,
                                          PayloadCapturePort.PayloadRecord respPayload,
                                          ChatRequest replay, String actualText) {
        ReplayResult done = withStatus(pending, ReplayResult.Status.COMPLETED, null);
        String expectedText = respPayload == null ? "" : extractText(respPayload.body());
        done = new ReplayResult(done.jobId(), done.sourceTraceId(),
                done.replayTraceId() != null ? done.replayTraceId() : jobId,
                done.status(), done.startedAt(), java.time.Instant.now(),
                done.kind(), done.safeReplay(), done.skippedMutatingTools(),
                Map.of("actualBytes", actualText.length(), "expectedBytes", expectedText.length()),
                done.errorMessage());
        jobs.put(jobId, done);

        // Sprint 2 P3:replay 完成后,异步把新 trace 的 REQUEST + RESPONSE 落 payload 表
        // → 让"replay 的结果"也能被再次 replay / diff / audit
        writeReplayedPayloads(jobId, replay, actualText, respPayload);

        log.info("replay done: jobId={} bytes={}/{}", jobId, actualText.length(), expectedText.length());
        return done;
    }

    /** 同步执行实际 replay(供 AdminReplayController 同步路径复用)。 */
    public ReplayResult replaySync(ReplayRequest req, String apiKey, String tenantIdHeader) {
        return replay(req, apiKey, tenantIdHeader);
    }

    /** callback POST 发送(默认用 java.net.http;失败仅日志)。 */
    private void postCallback(String url, ReplayResult result) {
        try {
            String body = "{\"jobId\":\"" + result.jobId()
                    + "\",\"status\":\"" + result.status()
                    + "\",\"sourceTraceId\":\"" + result.sourceTraceId() + "\"}";
            java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("Content-Type", "application/json");
            // Sprint 2 P2.2:HMAC 签名 + 时间戳
            if (callbackSigner != null) {
                long ts = java.time.Instant.now().toEpochMilli();
                String sig = callbackSigner.sign(ts, "POST", url, body);
                b.header(CallbackSigner.HEADER_TIMESTAMP, String.valueOf(ts))
                        .header(CallbackSigner.HEADER_SIGNATURE, "sha256=" + sig);
            }
            java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build()
                    .send(b.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                            java.net.http.HttpResponse.BodyHandlers.discarding());
            log.info("callback POST ok: url={} jobId={}{}", url, result.jobId(),
                    callbackSigner != null ? " (signed)" : "");
        } catch (Exception e) {
            log.warn("callback POST failed: url={} err={}", url, e.getMessage());
        }
    }

    /** 把 replay 产物异步落 payload 表(让 Replay 可被再 Replay)。 */
    private void writeReplayedPayloads(String jobId, ChatRequest replay, String actualText,
                                       PayloadCapturePort.PayloadRecord originalResp) {
        String newTraceId = jobId;
        try {
            // REQUEST:replay 时的请求(含 overrides 应用后)
            String modelId = replay.model() == null ? null : replay.model().value();
            String reqBody = "{\"prompt\":\"" + escapeJson(replay.prompt())
                    + "\",\"model\":" + (modelId == null ? "null" : "\"" + modelId + "\"")
                    + ",\"replayedFrom\":true}";
            payloadPort.capture(new PayloadCapturePort.PayloadRecord(
                    newTraceId, "replay", PayloadCapturePort.Role.REQUEST,
                    "messages_json", reqBody, reqBody.getBytes().length, java.time.Instant.now()));

            // RESPONSE:replay 产出 + original tokens(若有)
            Integer tokensIn = null, tokensOut = null;
            if (originalResp != null) {
                tokensIn = extractInt(originalResp.body(), "tokens_in");
                tokensOut = extractInt(originalResp.body(), "tokens_out");
            }
            String respBody = "{\"tokens_in\":" + (tokensIn == null ? 0 : tokensIn)
                    + ",\"tokens_out\":" + (tokensOut == null ? 0 : tokensOut)
                    + ",\"replayedFrom\":true}\n\n" + actualText;
            payloadPort.capture(new PayloadCapturePort.PayloadRecord(
                    newTraceId, "replay", PayloadCapturePort.Role.RESPONSE,
                    "text+meta", respBody, respBody.getBytes().length, java.time.Instant.now()));
            log.debug("replayed payloads written: traceId={}", newTraceId);
        } catch (Exception e) {
            log.warn("writeReplayedPayloads failed: {}", e.getMessage());
        }
    }

    private static Integer extractInt(String body, String key) {
        try {
            int i = body.indexOf("\"" + key + "\"");
            if (i < 0) return null;
            int s = body.indexOf(':', i);
            int e = body.indexOf(',', s);
            if (e < 0) e = body.indexOf('}', s);
            return Integer.parseInt(body.substring(s + 1, e).trim());
        } catch (Exception e) { return null; }
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** 取最近 job 的快照(供轮询)。 */
    public ReplayResult job(String jobId) {
        return jobs.get(jobId);
    }

    /** 列举最近 N 条 job(供 dashboard)。 */
    public List<ReplayResult> recentJobs(int limit) {
        List<ReplayResult> all = new ArrayList<>(jobs.values());
        all.sort((a, b) -> b.startedAt().compareTo(a.startedAt()));
        return all.subList(0, Math.min(limit, all.size()));
    }

    // ─── 内部 ───

    private OriginalRequest parseOriginal(String body) {
        try {
            Map<String, Object> map = objectMapper.readValue(body, new TypeReference<>() {});
            String prompt = String.valueOf(map.getOrDefault("prompt", ""));
            String model = map.get("model") == null ? null : String.valueOf(((String) map.get("model")).replaceAll("^\"|\"$", ""));
            return new OriginalRequest(prompt, model);
        } catch (Exception e) {
            // 退路:body 可能是 "{\"prompt\":\"...\"}" 简化形式
            return new OriginalRequest(extractSimplePrompt(body), null);
        }
    }

    private static String extractSimplePrompt(String body) {
        int i = body.indexOf("\"prompt\"");
        if (i < 0) return body;
        int s = body.indexOf('"', i + 9);
        int e = body.indexOf('"', s + 1);
        return e > s ? body.substring(s + 1, e) : body;
    }

    private ChatRequest buildChatRequest(OriginalRequest original, ReplayRequest req, String jobId) {
        String modelId = req.overrides().model() != null ? req.overrides().model() : original.model();
        String prompt = req.overrides().system() != null ? req.overrides().system() : original.prompt();
        ChatRequest cr = new ChatRequest(
                new SessionId(jobId),
                prompt,
                modelId == null ? null : new com.company.agentgateway.domain.shared.ModelId(modelId));
        return cr;
    }

    private static String extractText(String body) {
        // body 形如 "{meta-json}\n\n<text>";split on first \n\n
        int idx = body.indexOf("\n\n");
        return idx < 0 ? body : body.substring(idx + 2);
    }

    private static ReplayResult withStatus(ReplayResult r, ReplayResult.Status s, String err) {
        return new ReplayResult(r.jobId(), r.sourceTraceId(), r.replayTraceId(),
                s, r.startedAt(), java.time.Instant.now(), r.kind(), r.safeReplay(),
                r.skippedMutatingTools(), r.metadata(), err);
    }

    private record OriginalRequest(String prompt, String model) {}
}