package com.company.agentgateway.interfaces.chat;

import com.company.agentgateway.application.orchestration.ChatOrchestrator;
import com.company.agentgateway.application.orchestration.ChatRequest;
import com.company.agentgateway.application.orchestration.ChatStreamEvent;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.SessionId;
import com.company.agentgateway.infra.observability.trace.GatewayTracer;
import io.opentelemetry.api.trace.SpanKind;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 对话端点（spec §1.2 + §8.2）。
 *
 * <p>{@code POST /v1/chat/stream}：SSE 流式。订阅 orchestrator 的 Flux，逐事件推到 SseEmitter。
 * {@code POST /v1/chat}：非流式，返回完整文本。
 *
 * <p>调用链埋点(spec 2026-08-19 §5.1):gateway.chat SERVER span 在请求入口创建,
 * 异步编排/流式完成时结束 —— duration 即完整请求耗时。
 *
 * <p>Spring 4.0 严格模式:本类只用单构造器,且只注入 orchestrator + tracer 两个稳定 bean。
 * PayloadCaptureHelper 不注入此处(由 ChatOrchestrator 内部统一捕获,Sprint 2 P0 决策)。
 *
 * 空启动（无 nacos.addr/redis.addr）时不注册，避免缺依赖启动失败。
 */
@RestController
@RequestMapping("/v1/chat")
public class ChatController {

    private final ChatOrchestrator orchestrator;
    private final GatewayTracer tracer;

    @Autowired  // Spring 4.0 严格模式:显式标记主构造器
    public ChatController(ChatOrchestrator orchestrator, GatewayTracer tracer) {
        this.orchestrator = orchestrator;
        this.tracer = tracer;
    }

    /** SSE 流式对话。 */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatDto dto,
                             @RequestHeader(value = "X-API-Key", required = false) String apiKey,
                             @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        // 输入校验：prompt 必填且不超过上限，避免编排层 NPE / 超长 prompt 打爆 token 预算
        requireValidPrompt(dto);
        // 异步 SSE（长连接），timeout 较长（流式对话）
        SseEmitter emitter = new SseEmitter(300_000L);
        ChatRequest request = toRequest(dto);
        // 注:入口 captureRequest 已移至 ChatOrchestrator.run()(Sprint 2 P0 重构),
        // 避免 ChatController 依赖 PayloadCaptureHelper bean(Spring 4.0 严格模式
        // 下未注册的 bean 会导致构造器解析失败)。
        Map<String, String> attrs = baseAttrs(true, tenantId, dto);

        // SERVER span 包住整个异步流:在订阅线程上开启,doFinally 结束
        AutoCloseable span = tracer.span("gateway.chat", SpanKind.SERVER, attrs);
        orchestrator.orchestrate(request, apiKey, tenantId)
                .doFinally(signal -> closeSpan(span))
                .subscribe(
                        event -> emit(emitter, event),
                        error -> {
                            tracer.recordError(error);
                            emitter.completeWithError(error);
                        },
                        emitter::complete);
        return emitter;
    }

    /** 非流式对话：返回完整文本。订阅一次，收集 Delta + Error。 */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> chat(@RequestBody ChatDto dto,
                                    @RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                    @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        requireValidPrompt(dto);
        Map<String, String> attrs = baseAttrs(false, tenantId, dto);
        return tracer.withSpan("gateway.chat", SpanKind.SERVER, attrs, () -> {
            StringBuilder fullText = new StringBuilder();
            StringBuilder error = new StringBuilder();
            ChatRequest request = toRequest(dto);
            orchestrator.orchestrate(request, apiKey, tenantId).toStream().forEach(event -> {
                if (event instanceof ChatStreamEvent.Delta d) {
                    fullText.append(d.content());
                } else if (event instanceof ChatStreamEvent.Error e) {
                    error.append(e.message());
                }
            });
            if (error.length() > 0) {
                tracer.setAttributes(Map.of("error", error.toString()));
                return Map.<String, Object>of("error", error.toString());
            }
            return Map.<String, Object>of("response", fullText.toString());
        });
    }

    private static Map<String, String> baseAttrs(boolean stream, String tenantId, ChatDto dto) {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("stream", String.valueOf(stream));
        attrs.put("tenant_id", tenantId == null || tenantId.isBlank() ? "primary" : tenantId);
        if (dto.sessionId() != null) attrs.put("session_id", dto.sessionId());
        if (dto.model() != null) attrs.put("requested_model", dto.model());
        return attrs;
    }

    private void closeSpan(AutoCloseable span) {
        try {
            span.close();
        } catch (Exception ignored) {
            // span 结束失败不影响主链路
        }
    }

    private void emit(SseEmitter emitter, ChatStreamEvent event) {
        try {
            if (event instanceof ChatStreamEvent.Delta d) {
                emitter.send(SseEmitter.event().name("chunk").data(Map.of("content", d.content())));
            } else if (event instanceof ChatStreamEvent.ToolCallStarted t) {
                emitter.send(SseEmitter.event().name("tool_call_started").data(Map.of("agent", t.agentName())));
            } else if (event instanceof ChatStreamEvent.ToolCallResult t) {
                emitter.send(SseEmitter.event().name("tool_call_result").data(Map.of("agent", t.agentName(), "success", t.success())));
            } else if (event instanceof ChatStreamEvent.Complete c) {
                Map<String, Object> payload = new HashMap<>();
                payload.put("response", c.fullText());
                if (c.meta() != null) {
                    java.util.Map<String, Object> meta = new HashMap<>();
                    meta.put("model", c.meta().model());
                    meta.put("tokensIn", c.meta().tokensIn());
                    meta.put("tokensOut", c.meta().tokensOut());
                    // 提示缓存命中透明展示（done 事件 meta.cacheHit；非流式消费方同样读该字段）
                    meta.put("cacheHit", c.meta().cacheHit());
                    payload.put("meta", meta);
                }
                emitter.send(SseEmitter.event().name("done").data(payload));
            } else if (event instanceof ChatStreamEvent.Error e) {
                emitter.send(SseEmitter.event().name("error").data(Map.of("code", e.code(), "message", e.message())));
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    /** prompt 输入校验：缺失/空白 → 400；超长（>32k 字符）→ 400。 */
    private static void requireValidPrompt(ChatDto dto) {
        if (dto == null || dto.prompt() == null || dto.prompt().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt is required and must not be blank");
        }
        if (dto.prompt().length() > 32_768) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt too long (max 32768 chars)");
        }
    }

    private ChatRequest toRequest(ChatDto dto) {
        return new ChatRequest(
                dto.sessionId() == null ? null : new SessionId(dto.sessionId()),
                dto.prompt(),
                dto.model() == null ? null : new ModelId(dto.model()));
    }

    /** 请求 DTO（HTTP 反序列化）。 */
    public record ChatDto(String sessionId, String prompt, String model) {}
}
