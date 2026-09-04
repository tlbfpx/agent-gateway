package com.company.agentgateway.infra.llm.routing;

import com.company.agentgateway.domain.routing.RoutingMetricsPort;
import com.company.agentgateway.domain.routing.RoutingMetricsSnapshot;
import com.company.agentgateway.domain.shared.ModelId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * MicrometerRoutingMetricsAdapter(Round 10):从 MeterRegistry 聚合 routing.* 指标。
 *
 * <h2>指标约定</h2>
 * <ul>
 *   <li>{@code routing.request.total{model, result}} — Counter(success/failure)</li>
 *   <li>{@code routing.latency{model}} — Timer(p50/p99 由 registry 支持时取)</li>
 *   <li>{@code routing.cost.cents{model}} — Counter 累计;mean = avg cost cents</li>
 * </ul>
 *
 * <p>Micrometer 无值 → 视为无样本,返回 {@link RoutingMetricsSnapshot#empty(ModelId)}。
 */
public class MicrometerRoutingMetricsAdapter implements RoutingMetricsPort {

    private static final Logger log = LoggerFactory.getLogger(MicrometerRoutingMetricsAdapter.class);

    private final MeterRegistry registry;

    public MicrometerRoutingMetricsAdapter(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public List<RoutingMetricsSnapshot> snapshot(List<ModelId> modelIds) {
        List<RoutingMetricsSnapshot> result = new ArrayList<>();
        for (ModelId m : modelIds) {
            result.add(snapshotOne(m));
        }
        return result;
    }

    @Override
    public RoutingMetricsSnapshot snapshotOne(ModelId modelId) {
        Objects.requireNonNull(modelId, "modelId");
        Counter successCounter = registry.find("routing.request.total")
                .tags(Tags.of("model", modelId.value(), "result", "success"))
                .counter();
        Counter failureCounter = registry.find("routing.request.total")
                .tags(Tags.of("model", modelId.value(), "result", "failure"))
                .counter();
        Counter costCounter = registry.find("routing.cost.cents")
                .tags(Tags.of("model", modelId.value()))
                .counter();
        Timer latencyTimer = registry.find("routing.latency")
                .tags(Tags.of("model", modelId.value()))
                .timer();

        double total = (successCounter == null ? 0 : successCounter.count())
                + (failureCounter == null ? 0 : failureCounter.count());
        // 即使 success/failure counter 都为 0,只要 timer 有样本也算有数据
        // （让路由决策可基于 latency alone 而无需请求计数）
        double timerCount = latencyTimer == null ? 0 : latencyTimer.count();
        if (total == 0 && timerCount == 0) {
            return RoutingMetricsSnapshot.empty(modelId);
        }
        if (total == 0) total = timerCount; // 用 timer count 兜底
        double success = successCounter == null ? 0 : successCounter.count();
        double rate = total > 0 ? success / total : 1.0; // 无 counter 数据时假定 100% 成功（latency-only）
        Long p50 = (latencyTimer == null || latencyTimer.count() == 0) ? null
                : extractPercentile(latencyTimer, 0.5);
        BigDecimal avgCost = (costCounter == null || total == 0) ? null
                : BigDecimal.valueOf(costCounter.count() / total);
        return new RoutingMetricsSnapshot(modelId, rate, p50, avgCost, (long) total, Instant.now().minusSeconds(300));
    }

    /**
     * 提取 Timer 的百分位；SimpleMeterRegistry 在 histogram 未启用时
     * percentile() 返回 0/NaN，此时回退到 mean() 以保证下游拿到有效数值
     * （供 cost-aware 路由排序使用）。
     */
    private static long extractPercentile(Timer timer, double p) {
        double v = timer.percentile(p, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0) {
            v = timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        return Math.max(0L, Math.round(v));
    }
}