package com.company.agentgateway.infra.llm.routing;

import com.company.agentgateway.domain.shared.ModelId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MicrometerRoutingMetricsAdapter 测试(Round 10):验证 GW-RT-013。
 */
class MicrometerRoutingMetricsAdapterTest {

    @Test
    @DisplayName("GW-RT-013:无指标 → snapshot 为 empty")
    void emptyWhenNoMetrics() {
        MeterRegistry reg = new SimpleMeterRegistry();
        var adapter = new MicrometerRoutingMetricsAdapter(reg);

        var snap = adapter.snapshotOne(new ModelId("m1"));
        assertThat(snap.hasSamples()).isFalse();
        assertThat(snap.modelId().value()).isEqualTo("m1");
    }

    @Test
    @DisplayName("GW-RT-013:successRate = success / total")
    void successRateCalculated() {
        MeterRegistry reg = new SimpleMeterRegistry();
        var model = "m1";
        Counter.builder("routing.request.total")
                .tags(Tags.of("model", model, "result", "success"))
                .register(reg).increment(8);
        Counter.builder("routing.request.total")
                .tags(Tags.of("model", model, "result", "failure"))
                .register(reg).increment(2);

        var adapter = new MicrometerRoutingMetricsAdapter(reg);
        var snap = adapter.snapshotOne(new ModelId(model));
        assertThat(snap.hasSamples()).isTrue();
        assertThat(snap.successRate()).isCloseTo(0.8, org.assertj.core.api.Assertions.within(0.01));
        assertThat(snap.sampleCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("GW-RT-013:p50Latency 从 Timer 提取")
    void p50LatencyExtracted() {
        MeterRegistry reg = new SimpleMeterRegistry();
        var model = "m1";
        Timer timer = Timer.builder("routing.latency")
                .tags(Tags.of("model", model))
                .publishPercentiles(0.5, 0.99)
                .publishPercentileHistogram()
                .register(reg);
        // 记录 100 个样本,值 1-100ms
        for (int i = 1; i <= 100; i++) {
            timer.record(i, TimeUnit.MILLISECONDS);
        }

        var adapter = new MicrometerRoutingMetricsAdapter(reg);
        var snap = adapter.snapshotOne(new ModelId(model));
        assertThat(snap.hasSamples()).isTrue();
        assertThat(snap.p50LatencyMs()).isNotNull();
        // p50(可能因为 histogram 粒度 ±5ms;SimpleMeterRegistry 走估算)
        assertThat(snap.p50LatencyMs()).isBetween(1L, 100L);
    }

    @Test
    @DisplayName("avgCost 从 cost counter 累计")
    void avgCostExtracted() {
        MeterRegistry reg = new SimpleMeterRegistry();
        var model = "m1";
        Counter.builder("routing.request.total")
                .tags(Tags.of("model", model, "result", "success"))
                .register(reg).increment(4);
        Counter.builder("routing.cost.cents")
                .tags(Tags.of("model", model))
                .register(reg).increment(2.0); // 2 cents total

        var adapter = new MicrometerRoutingMetricsAdapter(reg);
        var snap = adapter.snapshotOne(new ModelId(model));
        assertThat(snap.avgCostCents()).isNotNull();
        assertThat(snap.avgCostCents().doubleValue()).isCloseTo(0.5,
                org.assertj.core.api.Assertions.within(0.01));
    }

    @Test
    @DisplayName("多模型查询 → 每个模型独立 snapshot")
    void multipleModels() {
        MeterRegistry reg = new SimpleMeterRegistry();
        Counter.builder("routing.request.total")
                .tags(Tags.of("model", "m1", "result", "success")).register(reg).increment(10);
        Counter.builder("routing.request.total")
                .tags(Tags.of("model", "m2", "result", "success")).register(reg).increment(5);
        Counter.builder("routing.request.total")
                .tags(Tags.of("model", "m2", "result", "failure")).register(reg).increment(5);

        var adapter = new MicrometerRoutingMetricsAdapter(reg);
        var snaps = adapter.snapshot(java.util.List.of(new ModelId("m1"), new ModelId("m2"), new ModelId("m3")));

        assertThat(snaps).hasSize(3);
        assertThat(snaps.get(0).successRate()).isEqualTo(1.0);
        assertThat(snaps.get(1).successRate()).isEqualTo(0.5);
        assertThat(snaps.get(2).hasSamples()).isFalse();
    }
}