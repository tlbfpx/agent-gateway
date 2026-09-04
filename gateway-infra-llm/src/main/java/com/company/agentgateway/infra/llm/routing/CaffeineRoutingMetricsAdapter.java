package com.company.agentgateway.infra.llm.routing;

import com.company.agentgateway.domain.routing.RoutingMetricsPort;
import com.company.agentgateway.domain.routing.RoutingMetricsSnapshot;
import com.company.agentgateway.domain.shared.ModelId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * CaffeineRoutingMetricsAdapter(Round 10):将 {@link CaffeineRoutingWindowStore} 适配为 RoutingMetricsPort。
 *
 * <p>无 MeterRegistry 时(Spring 装配中 MicrometerRoutingMetricsAdapter 缺失)的回退实现。
 * AutoRouter 装配时优先选 Micrometer,否则降级到 Caffeine。
 */
public class CaffeineRoutingMetricsAdapter implements RoutingMetricsPort {

    private final CaffeineRoutingWindowStore store;

    public CaffeineRoutingMetricsAdapter(CaffeineRoutingWindowStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public List<RoutingMetricsSnapshot> snapshot(List<ModelId> modelIds) {
        List<RoutingMetricsSnapshot> result = new ArrayList<>();
        for (ModelId m : modelIds) {
            result.add(store.snapshot(m));
        }
        return result;
    }
}