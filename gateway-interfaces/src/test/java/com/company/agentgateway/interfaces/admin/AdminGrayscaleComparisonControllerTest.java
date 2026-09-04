package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.model.Capability;
import com.company.agentgateway.domain.model.ModelDef;
import com.company.agentgateway.domain.observability.MetricPoint;
import com.company.agentgateway.domain.observability.MetricQueryRepository;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.infra.llm.model.JsonFileModelRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AdminGrayscaleComparisonController 契约单测：灰度组聚合 + 数据源降级。
 */
class AdminGrayscaleComparisonControllerTest {

    private JsonFileModelRegistry registry;
    private StubMetrics metrics;
    private MeterRegistry meters;

    @BeforeEach
    void setUp() throws Exception {
        registry = new JsonFileModelRegistry(Files.createTempFile("models", ".json"));
        metrics = new StubMetrics();
        meters = new SimpleMeterRegistry();
        registry.upsert(model("glm-4-plus", 80, new BigDecimal("0.05"), new BigDecimal("0.05")));
        registry.upsert(model("glm-4-plus-canary", 20, new BigDecimal("0.05"), new BigDecimal("0.05")));
        registry.upsert(model("minimax-abab6.5s-chat", "MiniMax abab6.5s", 100, BigDecimal.ONE, BigDecimal.ONE)); // 不同组
    }

    private static ModelDef model(String id, int weight, BigDecimal in, BigDecimal out) {
        return model(id, "GLM-4-Plus", weight, in, out);
    }

    private static ModelDef model(String id, String displayName, int weight, BigDecimal in, BigDecimal out) {
        return new ModelDef(new ModelId(id), "zhipu", displayName, "https://ep", "key-ref",
                java.util.Set.of(Capability.FUNCTION_CALLING), 8192, in, out, true, List.of(), null, weight);
    }

    @Test
    void 灰度组按displayName聚合且包含全部成员() {
        var c = new AdminGrayscaleComparisonController(registry, metrics, null);
        Map<String, Object> r = c.comparison("glm-4-plus", "24h");
        assertThat(r.get("group")).isEqualTo("GLM-4-Plus");
        assertThat(r.get("source")).isEqualTo("metrics-store");
        List<Map<String, Object>> members = (List<Map<String, Object>>) r.get("members");
        assertThat(members).extracting(m -> m.get("modelId"))
                .containsExactlyInAnyOrder("glm-4-plus", "glm-4-plus-canary");
        Map<String, Object> canary = members.stream()
                .filter(m -> "glm-4-plus-canary".equals(m.get("modelId"))).findFirst().orElseThrow();
        assertThat(canary.get("weight")).isEqualTo(20);
        assertThat(canary.get("requests")).isEqualTo(30.0);
        assertThat(canary.get("errors")).isEqualTo(3.0);
        assertThat((double) canary.get("errorRate")).isEqualTo(0.1);
        assertThat(canary.get("p50LatencyMs")).isEqualTo(400.0);
        assertThat(canary.get("p95LatencyMs")).isEqualTo(900.0);
        assertThat(canary.get("tokensIn")).isEqualTo(10_000L);
        assertThat(canary.get("tokensOut")).isEqualTo(2_000L);
        // cost = (10000*0.05 + 2000*0.05)/1000 = 0.6
        assertThat(new BigDecimal(String.valueOf(canary.get("costCny")))).isEqualByComparingTo("0.6");
    }

    @Test
    void PG不可用时内存降级() {
        meters.counter("chat.requests", Tags.of("model", "glm-4-plus")).increment(7);
        meters.counter("chat.errors", Tags.of("model", "glm-4-plus")).increment(1);
        meters.counter("llm.tokens.in", Tags.of("model", "glm-4-plus")).increment(1000);
        Timer t = Timer.builder("chat.latency").tags(Tags.of("tenant", "t", "model", "glm-4-plus", "success", "true"))
                .register(meters);
        t.record(Duration.ofMillis(200));
        t.record(Duration.ofMillis(400));

        var c = new AdminGrayscaleComparisonController(registry, null, meters);
        Map<String, Object> r = c.comparison("glm-4-plus", "24h");
        assertThat(r.get("source")).isEqualTo("memory");
        List<Map<String, Object>> members = (List<Map<String, Object>>) r.get("members");
        Map<String, Object> base = members.stream()
                .filter(m -> "glm-4-plus".equals(m.get("modelId"))).findFirst().orElseThrow();
        assertThat(base.get("requests")).isEqualTo(7.0);
        assertThat((double) base.get("errorRate")).isEqualTo(0.143); // round(3)
        assertThat((double) base.get("p50LatencyMs")).isEqualTo(300.0); // mean 兜底
        assertThat(base.get("tokensIn")).isEqualTo(1000L);
        // canary 无内存数据 → 0
        Map<String, Object> canary = members.stream()
                .filter(m -> "glm-4-plus-canary".equals(m.get("modelId"))).findFirst().orElseThrow();
        assertThat(canary.get("requests")).isEqualTo(0.0);
    }

    @Test
    void 双数据源皆无时source为none() {
        var c = new AdminGrayscaleComparisonController(registry, null, null);
        Map<String, Object> r = c.comparison("glm-4-plus-canary", "1h");
        assertThat(r.get("source")).isEqualTo("none");
        assertThat((List<?>) r.get("members")).hasSize(2);
    }

    @Test
    void 模型不存在404() {
        var c = new AdminGrayscaleComparisonController(registry, metrics, null);
        assertThatThrownBy(() -> c.comparison("nope", "24h"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    /** 内存 stub：canary 有数据（chat.requests=30 / errors=3 / 延迟三点 100,400,900 / tokens） */
    static class StubMetrics implements MetricQueryRepository {
        private static final String CANARY = "glm-4-plus-canary";
        private static final Instant BASE = Instant.now().minus(Duration.ofMinutes(10));

        @Override
        public List<MetricPoint> querySeries(String metricName, Map<String, String> tags, Instant from, Instant to) {
            if (!CANARY.equals(tags.get("model"))) return List.of();
            List<MetricPoint> out = new ArrayList<>();
            Instant t0 = BASE;
            if ("chat.latency.total_ms".equals(metricName)) {
                double[] totals = {100.0, 400.0, 900.0};
                for (int i = 0; i < totals.length; i++) {
                    out.add(new MetricPoint(metricName, tags, t0.plusSeconds(i * 60L), totals[i]));
                }
            } else if ("chat.latency.count".equals(metricName)) {
                for (int i = 0; i < 3; i++) {
                    out.add(new MetricPoint(metricName, tags, t0.plusSeconds(i * 60L), 1.0));
                }
            }
            return out;
        }

        @Override
        public OptionalDouble windowSum(String metricName, Map<String, String> tags, Instant from, Instant to) {
            if (!CANARY.equals(tags.get("model"))) return OptionalDouble.of(0.0);
            return switch (metricName) {
                case "chat.requests" -> OptionalDouble.of(30.0);
                case "chat.errors" -> OptionalDouble.of(3.0);
                case "llm.tokens.in" -> OptionalDouble.of(10_000.0);
                case "llm.tokens.out" -> OptionalDouble.of(2_000.0);
                default -> OptionalDouble.of(0.0);
            };
        }

        @Override
        public List<MetricBucket> queryBuckets(String metricName, Map<String, String> tags,
                                               Instant from, Instant to, int bucketSeconds) {
            return List.of();
        }
    }
}
