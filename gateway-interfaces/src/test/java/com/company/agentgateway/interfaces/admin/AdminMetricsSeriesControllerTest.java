package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.observability.MetricQueryRepository;
import com.company.agentgateway.domain.observability.MetricPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AdminMetricsSeriesController 契约单测(趋势查询,spec 2026-08-19 §6.2)。
 */
class AdminMetricsSeriesControllerTest {

    private StubMetrics metrics;
    private AdminMetricsSeriesController controller;

    @BeforeEach
    void setUp() {
        metrics = new StubMetrics();
        controller = new AdminMetricsSeriesController(metrics);
    }

    @Test
    void 返回分桶序列与元信息() {
        Map<String, Object> r = controller.series("chat.requests", "7d", null);
        assertThat(r.get("metric")).isEqualTo("chat.requests");
        assertThat(r.get("bucketSeconds")).isEqualTo(3600);  // 7d 默认 1h 桶
        assertThat((List<?>) r.get("points")).hasSize(1);
        assertThat(metrics.lastMetric).isEqualTo("chat.requests");
        assertThat(metrics.lastBucketSeconds).isEqualTo(3600);
    }

    @Test
    void range决定默认桶宽() {
        controller.series("chat.requests", "1h", null);
        assertThat(metrics.lastBucketSeconds).isEqualTo(60);
        controller.series("chat.requests", "6h", null);
        assertThat(metrics.lastBucketSeconds).isEqualTo(300);
        controller.series("chat.requests", "24h", null);
        assertThat(metrics.lastBucketSeconds).isEqualTo(1800);
    }

    @Test
    void 白名单外指标403() {
        assertThatThrownBy(() -> controller.series("pg.query.time", "1h", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void 未配置存储503引导() {
        assertThatThrownBy(() -> new AdminMetricsSeriesController(null).series("chat.requests", "1h", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("observability.storage");
    }

    static class StubMetrics implements MetricQueryRepository {
        String lastMetric;
        Integer lastBucketSeconds;

        @Override
        public List<MetricPoint> querySeries(String metricName, Map<String, String> tags, Instant from, Instant to) {
            return List.of();
        }

        @Override
        public OptionalDouble windowSum(String metricName, Map<String, String> tags, Instant from, Instant to) {
            return OptionalDouble.empty();
        }

        @Override
        public List<MetricBucket> queryBuckets(String metricName, Map<String, String> tags,
                                               Instant from, Instant to, int bucketSeconds) {
            lastMetric = metricName;
            lastBucketSeconds = bucketSeconds;
            return List.of(new MetricBucket(from, 42.0));
        }
    }
}
