package com.company.agentgateway.infra.observability.config;

import com.company.agentgateway.domain.observability.AlertStore;
import com.company.agentgateway.domain.observability.GatewayEvents;
import com.company.agentgateway.domain.observability.MetricQueryRepository;
import com.company.agentgateway.domain.observability.ObservabilityHooks;
import com.company.agentgateway.infra.observability.MicrometerObservabilityHooks;
import com.company.agentgateway.infra.observability.alert.AlertEngine;
import com.company.agentgateway.infra.observability.metrics.PgMetricsPublisher;
import com.company.agentgateway.infra.observability.trace.GatewayTracer;
import com.company.agentgateway.infra.observability.trace.PgSpanExporter;
import com.company.agentgateway.infra.persistence.observability.MetricsWriter;
import com.company.agentgateway.infra.persistence.observability.SpanWriter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;


/**
 * infra-observability 装配。
 *
 * <p>after = InfraPersistenceAutoConfiguration:SpanWriter/MetricsWriter/AlertStore 在
 * persistence 的自动配置里条件注册,@ConditionalOnBean 必须在其后评估,否则顺序不定导致 NOOP 降级。
 *
 * <ul>
 *   <li>有 MeterRegistry + Micrometer classpath → MicrometerObservabilityHooks;否则 NOOP</li>
 *   <li>有 observability.storage.jdbc-url(→ SpanWriter/MetricsWriter/AlertStore bean)→
 *       OTel SDK + PgSpanExporter + PgMetricsPublisher + AlertEngine(spec 2026-08-19 §5.2);
 *       否则 GatewayTracer.NOOP(trace 降级禁用)</li>
 * </ul>
 */
// afterName 用字符串:Boot 的 metrics 自动配置类不在本模块编译类path(仅运行时由 actuator 提供)
@org.springframework.boot.autoconfigure.AutoConfiguration(
        after = com.company.agentgateway.infra.persistence.config.InfraPersistenceAutoConfiguration.class,
        afterName = {"org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
                     "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"})
@ConditionalOnClass(MeterRegistry.class)
public class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean(ObservabilityHooks.class)
    public ObservabilityHooks micrometerObservabilityHooks(MeterRegistry registry) {
        return new MicrometerObservabilityHooks(registry);
    }

    // ================= OTel + PG 导出(spec 2026-08-19) =================

    @Bean
    @ConditionalOnBean(SpanWriter.class)
    public PgSpanExporter pgSpanExporter(SpanWriter spanWriter,
                                         @Value("${observability.export.batch-size:200}") int batchSize,
                                         @Value("${observability.export.flush-seconds:5}") int flushSeconds) {
        return new PgSpanExporter(spanWriter, batchSize * 20, batchSize, flushSeconds);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnBean(SpanWriter.class)
    public SdkTracerProvider sdkTracerProvider(PgSpanExporter exporter) {
        return SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnBean(SpanWriter.class)
    public OpenTelemetrySdk openTelemetrySdk(SdkTracerProvider tracerProvider) {
        return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }

    @Bean
    @ConditionalOnBean(SpanWriter.class)
    public GatewayTracer gatewayTracer(OpenTelemetrySdk otel) {
        org.slf4j.LoggerFactory.getLogger("observability").info("GatewayTracer 已启用(OTel 埋点 → PG)");
        return new GatewayTracer(otel);
    }

    @Bean
    @ConditionalOnMissingBean(GatewayTracer.class)
    public GatewayTracer noopGatewayTracer() {
        return GatewayTracer.NOOP;
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnBean({MeterRegistry.class, MetricsWriter.class})
    public PgMetricsPublisher pgMetricsPublisher(MeterRegistry registry, MetricsWriter writer,
                                                 @Value("${observability.metrics.interval-seconds:30}") int intervalSeconds) {
        org.slf4j.LoggerFactory.getLogger("observability").info("PgMetricsPublisher 已启用(30s delta 快照 → PG)");
        return new PgMetricsPublisher(registry, writer, intervalSeconds);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnBean({AlertStore.class, MetricQueryRepository.class})
    public AlertEngine alertEngine(AlertStore alertStore, MetricQueryRepository metrics,
                                   org.springframework.beans.factory.ObjectProvider<GatewayEvents> eventsProvider,
                                   @Value("${observability.alert.interval-seconds:30}") int intervalSeconds) {
        // 懒查找:WebhookEventBridge 的 GatewayEvents bean 可能晚于本自动配置注册
        GatewayEvents lazyEvents = (type, payload) ->
                eventsProvider.getIfAvailable(() -> GatewayEvents.NOOP).publish(type, payload);
        return new AlertEngine(alertStore, metrics, lazyEvents, intervalSeconds);
    }
}
