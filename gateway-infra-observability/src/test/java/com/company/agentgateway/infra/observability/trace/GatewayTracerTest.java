package com.company.agentgateway.infra.observability.trace;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GatewayTracer + PgSpanExporter 集成单测:span 创建 → 导出 → toRecord 转换。
 */
class GatewayTracerTest {

    private SdkTracerProvider tracerProvider;
    private CapturingExporter exporter;
    private GatewayTracer tracer;

    @BeforeEach
    void setUp() {
        exporter = new CapturingExporter();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        tracer = new GatewayTracer(sdk);
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown().join(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Test
    void withSpan产生带属性的span() {
        String result = tracer.withSpan("gateway.chat", SpanKind.SERVER,
                Map.of("tenant_id", "t1", "stream", "true"), () -> "ok");
        assertThat(result).isEqualTo("ok");

        assertThat(exporter.spans).hasSize(1);
        SpanDataCapture span = exporter.spans.get(0);
        assertThat(span.name).isEqualTo("gateway.chat");
        assertThat(span.attributes).containsEntry("tenant_id", "t1");
        assertThat(span.durationMs >= 0).isTrue();
    }

    @Test
    void 异常标记ERROR并上抛() {
        try {
            tracer.withSpan("llm.call", SpanKind.CLIENT, Map.of(), () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException ignored) {
        }
        assertThat(exporter.spans).hasSize(1);
        assertThat(exporter.spans.get(0).statusError).isTrue();
    }

    @Test
    void 嵌套span父子关系正确() {
        tracer.withSpan("gateway.chat", SpanKind.SERVER, Map.of(), () ->
                tracer.withSpan("agent.call", SpanKind.CLIENT, Map.of(), () -> "inner"));

        assertThat(exporter.spans).hasSize(2);
        SpanDataCapture child = exporter.spans.stream()
                .filter(s -> s.name.equals("agent.call")).findFirst().orElseThrow();
        SpanDataCapture parent = exporter.spans.stream()
                .filter(s -> s.name.equals("gateway.chat")).findFirst().orElseThrow();
        assertThat(child.parentSpanId).isNotNull();
        assertThat(child.traceId).isEqualTo(parent.traceId);
        assertThat(child.parentSpanId).isEqualTo(parent.spanId);
    }

    @Test
    void traceparent格式合法() {
        tracer.withSpan("gateway.chat", SpanKind.SERVER, Map.of(), () -> {
            String tp = tracer.currentTraceparent();
            assertThat(tp).isNotNull().matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
            return null;
        });
    }

    @Test
    void noop降级安全() {
        GatewayTracer noop = new GatewayTracer(null);
        assertThat(noop.enabled()).isFalse();
        assertThat(noop.hasActiveSpan()).isFalse();
        assertThat(noop.currentTraceparent()).isNull();
        // 全部 no-op 不抛异常
        assertThat(noop.withSpan("x", SpanKind.INTERNAL, Map.of(), () -> "v")).isEqualTo("v");
        noop.recordError(new RuntimeException());
        noop.setAttributes(Map.of("k", "v"));
    }

    /** 捕获导出数据的 exporter(替代真实 PG 写入)。 */
    static class CapturingExporter implements io.opentelemetry.sdk.trace.export.SpanExporter {
        final List<SpanDataCapture> spans = new ArrayList<>();
        private final io.opentelemetry.sdk.trace.export.SpanExporter delegate =
                io.opentelemetry.sdk.trace.export.SpanExporter.composite();

        @Override
        public io.opentelemetry.sdk.common.CompletableResultCode export(
                java.util.Collection<io.opentelemetry.sdk.trace.data.SpanData> data) {
            data.forEach(d -> {
                SpanDataCapture c = new SpanDataCapture();
                c.name = d.getName();
                c.traceId = d.getTraceId();
                c.spanId = d.getSpanId();
                c.parentSpanId = d.getParentSpanContext().isValid() ? d.getParentSpanContext().getSpanId() : null;
                c.statusError = d.getStatus().getStatusCode() == io.opentelemetry.api.trace.StatusCode.ERROR;
                c.durationMs = (d.getEndEpochNanos() - d.getStartEpochNanos()) / 1_000_000.0;
                c.attributes = new java.util.HashMap<>();
                d.getAttributes().forEach((k, v) -> c.attributes.put(k.getKey(), String.valueOf(v)));
                // 同时验证 toRecord 转换不抛异常
                PgSpanExporter.toRecord(d);
                spans.add(c);
            });
            return delegate.export(data);
        }

        @Override
        public io.opentelemetry.sdk.common.CompletableResultCode flush() {
            return delegate.flush();
        }

        @Override
        public io.opentelemetry.sdk.common.CompletableResultCode shutdown() {
            return delegate.shutdown();
        }
    }

    static class SpanDataCapture {
        String name;
        String traceId;
        String spanId;
        String parentSpanId;
        boolean statusError;
        double durationMs;
        Map<String, String> attributes;
    }
}
