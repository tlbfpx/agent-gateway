package com.company.agentgateway.infra.observability.trace;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 网关手动埋点门面(spec 2026-08-19 §5.1)。
 *
 * <p>统一入口:ChatController/ChatOrchestrator/A2aClient/ApiKeyAuthenticator 经此创建 span,
 * 不直接依赖 OTel API 细节。无 OTel 装配时全部方法为 no-op(NOO GatewayTracer)。
 *
 * <p>同步 span 用 try-with-resources 的 {@link AutoCloseable};
 * SSE/虚拟线程的异步链路在编排完成时显式 end。
 */
public class GatewayTracer {

    /** 无 OTel 时的空实现(未启用 observability.storage 时的降级)。 */
    public static final GatewayTracer NOOP = new GatewayTracer(null);

    private final Tracer tracer;

    public GatewayTracer(OpenTelemetry otel) {
        this.tracer = otel == null ? null : otel.getTracer("agent-gateway");
    }

    public boolean enabled() {
        return tracer != null;
    }

    /** 当前上下文中是否有活跃 span(下游埋点据此决定是否挂子 span)。 */
    public boolean hasActiveSpan() {
        return tracer != null && Span.current().getSpanContext().isValid();
    }

    /** 创建 span 并进入 scope;结束时 close 即 end(try-with-resources)。 */
    public AutoCloseable span(String name, SpanKind kind, Map<String, String> attributes) {
        if (!enabled()) return () -> {};
        Span span = tracer.spanBuilder(name)
                .setSpanKind(kind == null ? SpanKind.INTERNAL : kind)
                .startSpan();
        attributes.forEach(span::setAttribute);
        Scope scope = span.makeCurrent();
        return () -> {
            scope.close();
            span.end();
        };
    }

    /** 同步执行体包一层 span(成功 OK;异常记录 ERROR 并上抛)。 */
    public <T> T withSpan(String name, SpanKind kind, Map<String, String> attributes, Supplier<T> body) {
        if (!enabled()) return body.get();
        try (AutoCloseable s = span(name, kind, attributes)) {
            try {
                return body.get();
            } catch (RuntimeException e) {
                recordError(e);
                throw e;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 在 span 上记录异常(状态置 ERROR)。 */
    public void recordError(Throwable t) {
        if (hasActiveSpan()) {
            Span.current().recordException(t);
            Span.current().setStatus(StatusCode.ERROR);
        }
    }

    /** span 结束时追加属性(SSE 完成后补 tokens 等迟到信息)。 */
    public void setAttributes(Map<String, String> attributes) {
        if (hasActiveSpan()) {
            Span current = Span.current();
            attributes.forEach(current::setAttribute);
        }
    }

    /** 当前 traceId(响应/日志关联用);无活跃 span 返回 null。 */
    public String currentTraceId() {
        if (!hasActiveSpan()) return null;
        return Span.current().getSpanContext().getTraceId();
    }

    /** W3C traceparent(A2A 出站透传,spec §3 决策 2);无活跃 span 返回 null。 */
    public String currentTraceparent() {
        if (!hasActiveSpan()) return null;
        Span span = Span.current();
        return "00-" + span.getSpanContext().getTraceId() + "-"
                + span.getSpanContext().getSpanId() + "-01";
    }
}
