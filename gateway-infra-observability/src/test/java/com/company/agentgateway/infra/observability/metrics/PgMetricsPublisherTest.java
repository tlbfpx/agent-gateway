package com.company.agentgateway.infra.observability.metrics;

import com.company.agentgateway.domain.observability.MetricPoint;
import com.company.agentgateway.infra.persistence.observability.MetricsWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PgMetricsPublisher 快照逻辑单测(spec 2026-08-19 §4.2):
 * delta 语义(首次基线/增量/计数器重置)、白名单、tag 归一化(tenant→tenant_id)。
 */
class PgMetricsPublisherTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final List<List<MetricPoint>> batches = new ArrayList<>();
    private final MetricsWriter writer = points -> {
        batches.add(points);
        return points.size();
    };

    @Test
    void 首次快照建基线_第二次出增量() {
        PgMetricsPublisher publisher = new PgMetricsPublisher(registry, writer, 3600);

        registry.counter("chat.requests", "tenant", "t1", "model", "m1").increment(3);
        publisher.snapshot();
        assertThat(batches).isEmpty();  // 首次:基线,delta=0 不写

        registry.counter("chat.requests", "tenant", "t1", "model", "m1").increment(2);
        publisher.snapshot();

        assertThat(batches).hasSize(1);
        List<MetricPoint> pts = batches.get(0);
        assertThat(pts).hasSize(1);
        assertThat(pts.get(0).metricName()).isEqualTo("chat.requests");
        assertThat(pts.get(0).value()).isEqualTo(2.0);
        // tag 归一化:tenant → tenant_id(rollup 固定维度对齐)
        assertThat(pts.get(0).tags()).containsEntry("tenant_id", "t1").containsEntry("model", "m1");
    }

    @Test
    void timer产生count与totalMs两点() {
        PgMetricsPublisher publisher = new PgMetricsPublisher(registry, writer, 3600);
        Timer timer = registry.timer("chat.latency", "tenant", "t1");
        timer.record(Duration.ofMillis(100));
        publisher.snapshot();  // 基线

        timer.record(Duration.ofMillis(200));
        publisher.snapshot();

        List<MetricPoint> pts = batches.get(0);
        assertThat(pts).extracting(MetricPoint::metricName)
                .containsExactlyInAnyOrder("chat.latency.count", "chat.latency.total_ms");
        assertThat(pts.stream().filter(p -> p.metricName().endsWith(".count"))
                .findFirst().orElseThrow().value()).isEqualTo(1.0);
    }

    @Test
    void 白名单外指标不入库() {
        PgMetricsPublisher publisher = new PgMetricsPublisher(registry, writer, 3600);
        registry.counter("jvm.gc.live.data.size").increment(5);
        publisher.snapshot();
        publisher.snapshot();
        assertThat(batches).isEmpty();
    }

    @Test
    void 计数器重置不产生负增量() {
        PgMetricsPublisher publisher = new PgMetricsPublisher(registry, writer, 3600);
        io.micrometer.core.instrument.Counter c =
                registry.counter("chat.errors", "tenant", "t1");
        c.increment(5);
        publisher.snapshot();

        // 模拟重启后重置:新 counter 从 0 开始
        registry.remove(c);
        registry.counter("chat.errors", "tenant", "t1").increment(1);
        publisher.snapshot();

        // 重置被钳为 0;但同 series 第二次增量为正…此处验证不出现负值即可
        batches.forEach(b -> b.forEach(p -> assertThat(p.value()).isGreaterThanOrEqualTo(0)));
    }
}
