package com.company.agentgateway.infra.llm.routing;

import com.company.agentgateway.domain.routing.RoutingMetricsSnapshot;
import com.company.agentgateway.domain.shared.ModelId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CaffeineRoutingWindowStore 测试(Round 10)。
 */
class CaffeineRoutingWindowStoreTest {

    @Test
    void emptySnapshotWhenNoSamples() {
        var store = new CaffeineRoutingWindowStore();
        var snap = store.snapshot(new ModelId("m1"));
        assertThat(snap.hasSamples()).isFalse();
        assertThat(snap.modelId().value()).isEqualTo("m1");
    }

    @Test
    void successIncreasesRate() {
        var store = new CaffeineRoutingWindowStore();
        var m = new ModelId("m1");
        store.recordSuccess(m, 100L, new BigDecimal("0.10"));
        store.recordSuccess(m, 200L, new BigDecimal("0.20"));
        store.recordFailure(m);

        var snap = store.snapshot(m);
        assertThat(snap.hasSamples()).isTrue();
        assertThat(snap.sampleCount()).isEqualTo(3);
        assertThat(snap.successRate()).isCloseTo(2.0 / 3.0, org.assertj.core.api.Assertions.within(0.01));
        assertThat(snap.p50LatencyMs()).isBetween(100L, 200L);
    }

    @Test
    void avgCostCalculated() {
        var store = new CaffeineRoutingWindowStore();
        var m = new ModelId("m1");
        store.recordSuccess(m, 100L, new BigDecimal("0.10"));
        store.recordSuccess(m, 100L, new BigDecimal("0.30"));
        var snap = store.snapshot(m);
        assertThat(snap.avgCostCents()).isNotNull();
        assertThat(snap.avgCostCents().doubleValue()).isCloseTo(0.20, org.assertj.core.api.Assertions.within(0.01));
    }

    @Test
    void percentileHelperBasic() {
        var latencies = java.util.Arrays.asList(10L, 20L, 30L, 40L, 50L);
        long p50 = CaffeineRoutingWindowStore.percentile(latencies, 0.5);
        assertThat(p50).isEqualTo(30L); // 中位数

        long p0 = CaffeineRoutingWindowStore.percentile(latencies, 0.0);
        assertThat(p0).isEqualTo(10L);

        long p100 = CaffeineRoutingWindowStore.percentile(latencies, 1.0);
        assertThat(p100).isEqualTo(50L);
    }
}