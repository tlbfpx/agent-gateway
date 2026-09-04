package com.company.agentgateway.infra.a2a.config;

import com.company.agentgateway.domain.orchestration.ToolPort;
import com.company.agentgateway.domain.registry.EndpointSelector;
import com.company.agentgateway.domain.registry.RoundRobinEndpointSelector;
import com.company.agentgateway.infra.a2a.A2aClient;
import com.company.agentgateway.infra.a2a.A2aToolPort;
import com.company.agentgateway.infra.a2a.ResilientA2aClient;
import com.company.agentgateway.infra.observability.trace.GatewayTracer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * infra-a2a 运行期装配(spec 2026-08-19 §5.1 + B §4)。
 *
 * <p>装配顺序:
 * <ol>
 *   <li>WebClient + A2aClient(内层,含 GatewayTracer 埋点 + traceparent 透传)</li>
 *   <li>RoundRobinEndpointSelector + CircuitBreakerRegistry(熔断状态 → Micrometer)</li>
 *   <li>ResilientA2aClient(外层:重试 + 熔断 + 多实例)</li>
 *   <li>A2aToolPort 注入 ResilientA2aClient</li>
 * </ol>
 */
@Configuration
@ConditionalOnProperty(name = "a2a.enabled", havingValue = "true", matchIfMissing = true)
public class InfraA2aAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(WebClient.class)
    public WebClient a2aWebClient() {
        return WebClient.create();
    }

    @Bean
    @ConditionalOnMissingBean(A2aClient.class)
    public A2aClient a2aClient(WebClient a2aWebClient,
                               @Value("${a2a.timeout-ms:30000}") long timeoutMs,
                               GatewayTracer tracer) {
        return new A2aClient(a2aWebClient, timeoutMs, tracer);
    }

    /** 多实例选择器(spec B §4.3):RoundRobin + 失败转移。 */
    @Bean
    @ConditionalOnMissingBean(EndpointSelector.class)
    public EndpointSelector a2aEndpointSelector() {
        return new RoundRobinEndpointSelector();
    }

    /** 熔断器注册中心(按 agentName 共享):默认配置 + a2a.circuit.* 覆盖。 */
    @Bean
    @ConditionalOnMissingBean(CircuitBreakerRegistry.class)
    public CircuitBreakerRegistry a2aCircuitBreakerRegistry(
            @Value("${a2a.circuit.window-size:20}") int windowSize,
            @Value("${a2a.circuit.failure-rate:50}") float failureRate,
            @Value("${a2a.circuit.wait-open-seconds:30}") int waitOpen,
            @Value("${a2a.circuit.half-open-calls:3}") int halfOpen,
            MeterRegistry registry) {
        CircuitBreakerConfig cfg = CircuitBreakerConfig.custom()
                .slidingWindowSize(windowSize)
                .failureRateThreshold(failureRate)
                .waitDurationInOpenState(Duration.ofSeconds(waitOpen))
                .permittedNumberOfCallsInHalfOpenState(halfOpen)
                .build();
        CircuitBreakerRegistry cbr = CircuitBreakerRegistry.of(cfg);
        // 熔断状态 → Micrometer → PgMetricsPublisher → metrics_samples → Dashboard/告警(spec B §4.2)
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(cbr).bindTo(registry);
        return cbr;
    }

    /** 外层韧性装饰器(spec B §4):在 A2aClient 上加 重试/熔断/多实例。 */
    @Bean
    @ConditionalOnMissingBean(ResilientA2aClient.class)
    public ResilientA2aClient resilientA2aClient(A2aClient a2aClient,
                                                CircuitBreakerRegistry circuitRegistry,
                                                EndpointSelector selector,
                                                @Value("${a2a.retry.max-attempts:3}") int maxAttempts,
                                                @Value("${a2a.retry.backoff-ms:100}") long backoffMs) {
        return new ResilientA2aClient(a2aClient, circuitRegistry, selector, maxAttempts, Duration.ofMillis(backoffMs));
    }

    @Bean
    @ConditionalOnMissingBean(ToolPort.class)
    public ToolPort a2aToolPort(ResilientA2aClient resilientA2aClient,
                                org.springframework.beans.factory.ObjectProvider<com.company.agentgateway.domain.iam.AuthorizationService> authorizationServiceProvider) {
        // D1 spec §GW-RBAC-006：可选注入 AuthorizationService（A2A 调用前二次校验）
        return new A2aToolPort(resilientA2aClient, authorizationServiceProvider.getIfAvailable());
    }
}