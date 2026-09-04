package com.company.agentgateway.interfaces.chat;

import com.company.agentgateway.application.orchestration.ChatOrchestrator;
import com.company.agentgateway.application.orchestration.ChatRequest;
import com.company.agentgateway.application.orchestration.ChatStreamEvent;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.infra.observability.trace.GatewayTracer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.SpanKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI 兼容对话端点（spec 产品 #1：让上游零改造接入）。
 *
 * <p>实现 {@code POST /v1/chat/completions}（流式 + 非流式）与 {@code POST /v1/embeddings}（501 stub）。
 *
 * <p>与 ChatController 行为的关键差异（任务书 §错误码映射）：
 * <ul>
 *   <li>Error.code 含 AUTH → 401 invalid_api_key；含 MODEL → 400 model_not_found；其余 → 502 upstream_error。
 *       ChatController 仍返回 200 + error 字段（保留旧 /v1/chat 行为不变），新控制器必须返回对应 HTTP 状态码，
 *       否则 OpenAI SDK 会按 HTTP 200 当作成功。</li>
 *   <li>SSE 帧格式：裸 {@code data: ...} 行（无 {@code event:}），对齐 OpenAI 官方 wire format；现有
 *       ChatController 的 {@code SseEmitter.event().name("chunk")} 会让 OpenAI SDK 解析失败。</li>
 *   <li>鉴权：除 X-API-Key 外，额外接受 {@code Authorization: Bearer sk-xxx}（OpenAI SDK 默认头）。</li>
 * </ul>
 *
 * <p>ToolCallStarted / ToolCallResult 在 OpenAI 协议里无对应位置，直接忽略不发帧，避免污染客户端拼接的正文。
 */
@RestController
@RequestMapping("/v1")
public class OpenAiChatController {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatController.class);

    /** 非流式与流式帧 object 字段。 */
    private static final String OBJECT_COMPLETION = "chat.completion";
    private static final String OBJECT_CHUNK = "chat.completion.chunk";

    /** SSE timeout 与 ChatController 对齐。 */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    private final ChatOrchestrator orchestrator;
    private final GatewayTracer tracer;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiChatController(ChatOrchestrator orchestrator, GatewayTracer tracer) {
        this.orchestrator = orchestrator;
        this.tracer = tracer;
    }

    /**
     * {@code POST /v1/chat/completions}。
     *
     * <p>由 {@code stream} 请求体字段路由：true → SSE 流式；false 或缺失 → 非流式。
     */
    @PostMapping(value = "/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object chatCompletions(@RequestBody(required = false) OpenAiChatDto.ChatCompletionRequest req,
                                 @RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                 @RequestHeader(value = "Authorization", required = false) String authorization,
                                 @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        if (req == null || req.messages() == null || req.messages().isEmpty()) {
            throw badRequest("messages is required and must not be empty", "invalid_messages");
        }
        String prompt;
        try {
            prompt = OpenAiMessageMapper.flatten(req.messages());
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage(), "invalid_messages");
        }
        if (prompt.isBlank()) {
            throw badRequest("prompt is blank after flatten", "invalid_messages");
        }
        if (prompt.length() > OpenAiMessageMapper.MAX_PROMPT_LENGTH) {
            throw badRequest("prompt too long (max " + OpenAiMessageMapper.MAX_PROMPT_LENGTH + " chars)",
                    "invalid_messages");
        }
        String effectiveApiKey = resolveApiKey(apiKey, authorization);
        ChatRequest chatRequest = new ChatRequest(
                null,
                prompt,
                req.model() == null ? null : new ModelId(req.model()));
        Map<String, String> attrs = baseAttrs(req.stream() != null && req.stream(), tenantId, req);
        boolean stream = req.stream() != null && req.stream();
        if (stream) {
            return streamChatCompletions(chatRequest, effectiveApiKey, tenantId, req, attrs);
        }
        return nonStreamChatCompletions(chatRequest, effectiveApiKey, tenantId, req, attrs);
    }

    /** {@code POST /v1/embeddings}：占位 501。 */
    @PostMapping(value = "/embeddings", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OpenAiChatDto.ErrorEnvelope> embeddings() {
        OpenAiChatDto.ErrorBody body = new OpenAiChatDto.ErrorBody(
                "embeddings endpoint is not implemented yet",
                "not_implemented",
                "not_implemented");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(new OpenAiChatDto.ErrorEnvelope(body));
    }

    // --- 非流式 -----------------------------------------------------------------------

    private ResponseEntity<Object> nonStreamChatCompletions(ChatRequest chatRequest,
                                                            String apiKey,
                                                            String tenantId,
                                                            OpenAiChatDto.ChatCompletionRequest req,
                                                            Map<String, String> attrs) {
        // 同步阻塞消费（与 ChatController 风格一致）：在 span scope 内一次性订阅到结束
        return tracer.withSpan("gateway.openai.chat", SpanKind.SERVER, attrs, () -> {
            StringBuilder fullText = new StringBuilder();
            ChatStreamEvent.Complete[] completeHolder = new ChatStreamEvent.Complete[1];
            ChatStreamEvent.Error[] errorHolder = new ChatStreamEvent.Error[1];
            orchestrator.orchestrate(chatRequest, apiKey, tenantId)
                    .toStream()
                    .forEach(event -> {
                        if (event instanceof ChatStreamEvent.Delta d) {
                            fullText.append(d.content());
                        } else if (event instanceof ChatStreamEvent.Complete c) {
                            completeHolder[0] = c;
                        } else if (event instanceof ChatStreamEvent.Error e) {
                            errorHolder[0] = e;
                        }
                        // ToolCallStarted / ToolCallResult：OpenAI 协议无对应位置，忽略
                    });
            ChatStreamEvent.Error err = errorHolder[0];
            if (err != null) {
                throw mapToResponseStatus(err);
            }
            return ResponseEntity.ok(buildNonStreamBody(req, fullText.toString(), completeHolder[0]));
        });
    }

    private Map<String, Object> buildNonStreamBody(OpenAiChatDto.ChatCompletionRequest req,
                                                  String fullText,
                                                  ChatStreamEvent.Complete complete) {
        String id = OpenAiMessageMapper.newCompletionId();
        long created = System.currentTimeMillis() / 1000L;
        // model：灰度分流后的实际模型（Meta.model）；缺省回落请求体 model；仍缺省 unknown
        String resolvedModel = complete != null && complete.meta() != null && complete.meta().model() != null
                ? complete.meta().model()
                : (req.model() != null ? req.model() : "unknown");
        int promptTokens;
        int completionTokens;
        if (complete != null && complete.meta() != null) {
            promptTokens = (int) Math.min(Integer.MAX_VALUE, complete.meta().tokensIn());
            completionTokens = (int) Math.min(Integer.MAX_VALUE, complete.meta().tokensOut());
        } else {
            // Meta 缺失 → chars/4 兜底估算
            promptTokens = OpenAiMessageMapper.estimateTokens(OpenAiMessageMapper.flatten(req.messages()));
            completionTokens = OpenAiMessageMapper.estimateTokens(fullText);
        }
        OpenAiChatDto.Usage usage = new OpenAiChatDto.Usage(promptTokens, completionTokens,
                promptTokens + completionTokens);
        OpenAiChatDto.Message msg = new OpenAiChatDto.Message("assistant", fullText);
        OpenAiChatDto.Choice choice = new OpenAiChatDto.Choice(0, msg, "stop");
        OpenAiChatDto.ChatCompletionResponse body = new OpenAiChatDto.ChatCompletionResponse(
                id, OBJECT_COMPLETION, created, resolvedModel, List.of(choice), usage);
        try {
            return mapper.readValue(mapper.writeValueAsString(body), Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize chat completion response", e);
        }
    }

    // --- 流式 -----------------------------------------------------------------------

    private SseEmitter streamChatCompletions(ChatRequest chatRequest,
                                              String apiKey,
                                              String tenantId,
                                              OpenAiChatDto.ChatCompletionRequest req,
                                              Map<String, String> attrs) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // span 包住整个异步流
        AutoCloseable span = tracer.span("gateway.openai.chat", SpanKind.SERVER, attrs);
        String id = OpenAiMessageMapper.newCompletionId();
        long created = System.currentTimeMillis() / 1000L;
        String requestedModel = req.model();
        // 首帧开关：true 时下一帧只发 role（不再发），后续帧只发 content
        java.util.concurrent.atomic.AtomicBoolean firstFrame = new java.util.concurrent.atomic.AtomicBoolean(true);

        orchestrator.orchestrate(chatRequest, apiKey, tenantId)
                .doFinally(signal -> closeSpan(span))
                .subscribe(
                        event -> emitStream(emitter, event, id, created, requestedModel, firstFrame),
                        error -> {
                            tracer.recordError(error);
                            try {
                                // 已开始推流 → 只能塞 error 帧后正常结束（HTTP 状态码已 commit，无法改）
                                emitter.send(SseEmitter.event().data(
                                        mapper.writeValueAsString(errorBody("upstream_error",
                                                "upstream stream error: " + error.getMessage(),
                                                "upstream_error")),
                                        MediaType.TEXT_PLAIN));
                            } catch (Exception ignored) {
                                // 序列化/发送失败：忽略，让 emitter 走 error 关闭
                            }
                            emitter.complete();
                        },
                        emitter::complete);

        return emitter;
    }

    private void emitStream(SseEmitter emitter,
                            ChatStreamEvent event,
                            String id,
                            long created,
                            String requestedModel,
                            java.util.concurrent.atomic.AtomicBoolean firstFrame) {
        try {
            if (event instanceof ChatStreamEvent.Delta d) {
                OpenAiChatDto.Delta delta;
                if (firstFrame.compareAndSet(true, false)) {
                    // 首帧：只发 role delta，告诉客户端 assistant 流开始
                    delta = new OpenAiChatDto.Delta("assistant", null);
                } else {
                    // 后续帧：只发 content delta
                    delta = new OpenAiChatDto.Delta(null, d.content());
                }
                OpenAiChatDto.ChunkChoice ch = new OpenAiChatDto.ChunkChoice(0, delta, null);
                OpenAiChatDto.ChunkResponse frame = new OpenAiChatDto.ChunkResponse(
                        id, OBJECT_CHUNK, created,
                        requestedModel == null ? "unknown" : requestedModel,
                        List.of(ch));
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(frame), MediaType.TEXT_PLAIN));
            } else if (event instanceof ChatStreamEvent.Complete c) {
                // 收尾帧：finish_reason=stop 且 delta 为空对象
                OpenAiChatDto.ChunkChoice stop = new OpenAiChatDto.ChunkChoice(0,
                        new OpenAiChatDto.Delta(null, null), "stop");
                String resolvedModel = c.meta() != null && c.meta().model() != null
                        ? c.meta().model()
                        : (requestedModel == null ? "unknown" : requestedModel);
                OpenAiChatDto.ChunkResponse frame = new OpenAiChatDto.ChunkResponse(
                        id, OBJECT_CHUNK, created, resolvedModel, List.of(stop));
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(frame), MediaType.TEXT_PLAIN));
                // [DONE] 必须是裸文本行，不是 JSON
                emitter.send(SseEmitter.event().data("[DONE]", MediaType.TEXT_PLAIN));
            } else if (event instanceof ChatStreamEvent.Error e) {
                // 流式已 commit headers → 只能塞 error 帧（HTTP 状态码翻译由非流式路径承担）
                emitter.send(SseEmitter.event().data(
                        mapper.writeValueAsString(mapErrorEnvelope(e)),
                        MediaType.TEXT_PLAIN));
            }
            // ToolCallStarted / ToolCallResult：OpenAI 协议无对应位置，忽略
        } catch (JsonProcessingException e) {
            emitter.completeWithError(e);
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    // --- 错误码映射 --------------------------------------------------------------------

    private static ResponseStatusException mapToResponseStatus(ChatStreamEvent.Error e) {
        String lower = e.code() == null ? "" : e.code().toUpperCase(Locale.ROOT);
        if (lower.contains("AUTH") || lower.contains("UNAUTHORIZED")) {
            return new OpenAiErrorResponseStatusException(HttpStatus.UNAUTHORIZED,
                    errorBody("invalid_request_error", "invalid api key", "invalid_api_key"));
        }
        if (lower.contains("MODEL") || lower.contains("NOT_FOUND")) {
            return new OpenAiErrorResponseStatusException(HttpStatus.BAD_REQUEST,
                    errorBody("invalid_request_error",
                            "model not found: " + e.message(), "model_not_found"));
        }
        return new OpenAiErrorResponseStatusException(HttpStatus.BAD_GATEWAY,
                errorBody("upstream_error", e.message(), null));
    }

    private static OpenAiChatDto.ErrorEnvelope mapErrorEnvelope(ChatStreamEvent.Error e) {
        String lower = e.code() == null ? "" : e.code().toUpperCase(Locale.ROOT);
        if (lower.contains("AUTH") || lower.contains("UNAUTHORIZED")) {
            return errorBody("invalid_request_error", "invalid api key", "invalid_api_key");
        }
        if (lower.contains("MODEL") || lower.contains("NOT_FOUND")) {
            return errorBody("invalid_request_error",
                    "model not found: " + e.message(), "model_not_found");
        }
        return errorBody("upstream_error", e.message(), null);
    }

    private static OpenAiChatDto.ErrorEnvelope errorBody(String type, String message, String code) {
        return new OpenAiChatDto.ErrorEnvelope(new OpenAiChatDto.ErrorBody(message, type, code));
    }

    private static OpenAiErrorResponseStatusException badRequest(String message, String code) {
        return new OpenAiErrorResponseStatusException(HttpStatus.BAD_REQUEST,
                errorBody("invalid_request_error", message, code));
    }

    /**
     * 携带 OpenAI ErrorEnvelope 的 ResponseStatusException，让 Spring MVC 把它序列化为 OpenAI 错误格式。
     *
     * <p>Spring Boot 4 的 {@link ResponseStatusException#getBody()} 返回 ProblemDetail，
     * 这里命名为 {@code envelope()} 避免与父类签名冲突，由本类内的 {@link #handle} 显式序列化。
     */
    public static class OpenAiErrorResponseStatusException extends ResponseStatusException {
        private final OpenAiChatDto.ErrorEnvelope envelope;

        public OpenAiErrorResponseStatusException(HttpStatus status, OpenAiChatDto.ErrorEnvelope body) {
            super(status, body.error() != null ? body.error().message() : status.getReasonPhrase());
            this.envelope = body;
        }

        public OpenAiChatDto.ErrorEnvelope envelope() {
            return envelope;
        }
    }

    /**
     * 把 OpenAiErrorResponseStatusException 转成 OpenAI 错误格式（覆盖默认的 Spring Boot 错误格式）。
     */
    @ExceptionHandler(OpenAiErrorResponseStatusException.class)
    public ResponseEntity<OpenAiChatDto.ErrorEnvelope> handle(OpenAiErrorResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(ex.envelope());
    }

    // --- 通用工具 --------------------------------------------------------------------

    private static Map<String, String> baseAttrs(boolean stream, String tenantId,
                                                 OpenAiChatDto.ChatCompletionRequest req) {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("stream", String.valueOf(stream));
        attrs.put("tenant_id", tenantId == null || tenantId.isBlank() ? "primary" : tenantId);
        if (req != null && req.model() != null) {
            attrs.put("requested_model", req.model());
        }
        return attrs;
    }

    /**
     * 鉴权头归一：X-API-Key 优先；缺失时从 {@code Authorization: Bearer sk-xxx} 剥取 token。
     */
    private static String resolveApiKey(String apiKey, String authorization) {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        if (authorization != null && !authorization.isBlank()) {
            String trimmed = authorization.trim();
            if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return trimmed.substring(7).trim();
            }
            return trimmed;
        }
        return null;
    }

    private void closeSpan(AutoCloseable span) {
        try {
            span.close();
        } catch (Exception ignored) {
            // span 结束失败不影响主链路
        }
    }
}