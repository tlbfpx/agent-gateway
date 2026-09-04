package com.company.agentgateway.infra.a2a;

import com.company.agentgateway.domain.orchestration.ToolEvent;
import com.company.agentgateway.infra.observability.trace.GatewayTracer;
import io.opentelemetry.api.trace.SpanKind;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

/**
 * A2A 协议客户端：向远程 Agent 发起 JSON-RPC over HTTP+SSE 调用，返回 ToolEvent 流。
 *
 * <p>协议（spec §2.1）：POST {endpointUrl}，body=JSON-RPC（method=invoke, params=argsJson），响应为 SSE 流。
 * 用 WebClient 的 ServerSentEvent 解码器（勘误修订5），无需手写 SSE 行解析。
 *
 * <p>调用链埋点(spec 2026-08-19 §5.1):agent.call CLIENT span 包住整个流;
 * 出站透传 W3C traceparent header(§3 决策 2)—— 远程 Agent 埋点可延续链路。
 */
public class A2aClient {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final long timeoutMs;
    private final GatewayTracer tracer;

    public A2aClient(WebClient webClient, long timeoutMs) {
        this(webClient, timeoutMs, GatewayTracer.NOOP);
    }

    public A2aClient(WebClient webClient, long timeoutMs, GatewayTracer tracer) {
        this.webClient = webClient;
        this.timeoutMs = timeoutMs;
        this.tracer = tracer == null ? GatewayTracer.NOOP : tracer;
    }

    /**
     * 调用远程 Agent，返回 ToolEvent 流（SSE → map）。
     *
     * @param endpointUrl Agent 的 A2A 调用地址（AgentCard.endpointUrl）
     * @param agentName   Agent 名（用于 JSON-RPC id/上下文）
     * @param argsJson    入参（JSON 文本）
     * @return Flux&lt;ToolEvent&gt;；超时由 Reactor timeout 控制为 ToolEvent.Error
     */
    public Flux<ToolEvent> invokeStream(String endpointUrl, String agentName, String argsJson) {
        Map<String, Object> jsonRpc = Map.of(
                "jsonrpc", "2.0",
                "method", "invoke",
                "params", argsJson == null ? "" : argsJson,
                "id", agentName
        );
        Map<String, String> spanAttrs = new HashMap<>();
        spanAttrs.put("agent_name", agentName);
        spanAttrs.put("timeout_ms", String.valueOf(timeoutMs));
        spanAttrs.put("a2a.endpoint", endpointUrl);

        // CLIENT span 包住整个响应流;traceparent 透传给远程 Agent
        AutoCloseable span = tracer.span("agent.call", SpanKind.CLIENT, spanAttrs);
        String traceparent = tracer.currentTraceparent();

        WebClient.RequestBodySpec spec = webClient.post()
                .uri(endpointUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM);
        if (traceparent != null) {
            spec.header("traceparent", traceparent);
        }

        return spec.bodyValue(jsonRpc)
                .retrieve()
                .bodyToFlux(SSE_TYPE)
                .map(SseEventMapper::toToolEvent)
                .timeout(java.time.Duration.ofMillis(timeoutMs),
                        reactor.core.publisher.Mono.fromSupplier(
                                () -> (ToolEvent) new ToolEvent.Error("A2A_TIMEOUT", "invoke timed out after " + timeoutMs + "ms")))
                // 其他错误（连接失败、4xx/5xx、协议错）→ 统一转 ToolEvent.Error，不向上抛
                .onErrorResume(e -> {
                    tracer.recordError(e);
                    return Flux.just(new ToolEvent.Error(
                            "A2A_ERROR", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                })
                // 流终结(完成/取消/错误)时结束 span
                .doFinally(signal -> {
                    try {
                        span.close();
                    } catch (Exception ignored) {
                        // span 结束失败不影响主链路
                    }
                });
    }
}
