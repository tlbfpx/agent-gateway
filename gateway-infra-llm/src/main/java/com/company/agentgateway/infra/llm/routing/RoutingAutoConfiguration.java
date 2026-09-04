package com.company.agentgateway.infra.llm.routing;

import com.company.agentgateway.domain.routing.RoutingMetricsPort;
import com.company.agentgateway.domain.routing.RoutingPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Routing 自动装配(Round 10):
 * 提供 DefaultRoutingService(RoutingPort 实现) + 两个 RoutingMetricsPort 实现(Micrometer / Caffeine)。
 *
 * <p>{@code gateway.routing.enabled=false} 时不装配;ChatOrchestrator 走原 selectModel(GW-RT-001)。
 */
@Configuration
@ConditionalOnProperty(name = "gateway.routing.enabled", havingValue = "true", matchIfMissing = true)
public class RoutingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RoutingAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(RoutingPort.class)
    public DefaultRoutingService defaultRoutingService() {
        log.info("Routing:DefaultRoutingService 初始化");
        return new DefaultRoutingService();
    }

    @Bean
    @ConditionalOnMissingBean(CaffeineRoutingWindowStore.class)
    public CaffeineRoutingWindowStore caffeineRoutingWindowStore() {
        log.info("Routing:CaffeineRoutingWindowStore 初始化(5min 滑动窗口 fallback)");
        return new CaffeineRoutingWindowStore();
    }

    /**
     * Micrometer 优先;若 MeterRegistry 不可用(无 actuator),降级为 Caffeine。
     */
    @Bean
    @ConditionalOnMissingBean(RoutingMetricsPort.class)
    public RoutingMetricsPort routingMetricsPort(ObjectProvider<MeterRegistry> registry,
                                                 CaffeineRoutingWindowStore store) {
        MeterRegistry reg = registry.getIfAvailable();
        if (reg != null) {
            log.info("Routing:使用 MicrometerRoutingMetricsAdapter");
            return new MicrometerRoutingMetricsAdapter(reg);
        }
        log.info("Routing:无 MeterRegistry,降级到 CaffeineRoutingMetricsAdapter");
        return new CaffeineRoutingMetricsAdapter(store);
    }
}