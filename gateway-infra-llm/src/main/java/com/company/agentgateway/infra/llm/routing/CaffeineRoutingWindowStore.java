package com.company.agentgateway.infra.llm.routing;

import com.company.agentgateway.domain.shared.ModelId;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * CaffeineRoutingWindowStore(Round 10):5min 滑动窗口 fallback。
 *
 * <p>无 MeterRegistry 时启用;在内存中维护每个模型的 success/fail 计数 + latency 列表 + cost 总和。
 * 提供 {@link #snapshot(ModelId)} 聚合为 RoutingMetricsSnapshot 形态。
 *
 * <p>设计要点:
 * <ul>
 *   <li>Caffeine expireAfterWrite=5min 自动清理(每次写入/读取触发;实际近似滑动窗口)</li>
 *   <li>latency 用 ArrayList 存;样本上限 1000,超出按 FIFO 淘汰(简化;精确 percentile 留 P3)</li>
 *   <li>cost 用 BigDecimal LongAdder 累计</li>
 * </ul>
 */
public class CaffeineRoutingWindowStore {

    private final Cache<ModelId, WindowBucket> buckets;
    private final Duration windowSize;

    public CaffeineRoutingWindowStore() {
        this(Duration.ofMinutes(5));
    }

    public CaffeineRoutingWindowStore(Duration windowSize) {
        this.windowSize = windowSize;
        this.buckets = Caffeine.newBuilder()
                .expireAfterWrite(windowSize)
                .maximumSize(10_000)
                .build();
    }

    /** 记录成功样本。 */
    public void recordSuccess(ModelId modelId, long latencyMs, BigDecimal costCents) {
        WindowBucket b = buckets.get(modelId, k -> new WindowBucket());
        b.successCount.increment();
        b.totalCount.increment();
        if (costCents != null) {
            // costCents 单位约定为"分";若调用方传"元"(如 BigDecimal("0.10"))则 ×100 归一
            long cents = costCents.multiply(BigDecimal.valueOf(100)).longValue();
            b.totalCost.add(cents);
        }
        synchronized (b.latencies) {
            if (b.latencies.size() >= 1000) b.latencies.remove(0);
            b.latencies.add(latencyMs);
        }
    }

    /** 记录失败样本。 */
    public void recordFailure(ModelId modelId) {
        WindowBucket b = buckets.get(modelId, k -> new WindowBucket());
        b.failureCount.increment();
        b.totalCount.increment();
    }

    /** 聚合为快照。 */
    public com.company.agentgateway.domain.routing.RoutingMetricsSnapshot snapshot(ModelId modelId) {
        WindowBucket b = buckets.getIfPresent(modelId);
        if (b == null || b.totalCount.sum() == 0) {
            return com.company.agentgateway.domain.routing.RoutingMetricsSnapshot.empty(modelId);
        }
        long total = b.totalCount.sum();
        long success = b.successCount.sum();
        double rate = (double) success / total;
        Long p50;
        synchronized (b.latencies) {
            p50 = b.latencies.isEmpty() ? null : percentile(b.latencies, 0.5);
        }
        BigDecimal avgCost;
        long totalCostCents = b.totalCost.sum();
        if (totalCostCents == 0) {
            avgCost = null;
        } else {
            // totalCost 是 cents 累计;avg 转回元(/total /100)
            avgCost = BigDecimal.valueOf(totalCostCents).divide(
                    BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(100)), 4,
                    java.math.RoundingMode.HALF_UP);
        }
        Instant windowEnd = Instant.now();
        Instant start = windowEnd.minus(windowSize);
        return new com.company.agentgateway.domain.routing.RoutingMetricsSnapshot(
                modelId, rate, p50, avgCost, total, start);
    }

    /** 简单中位数(线性插值);样本 < 100 时精确。 */
    static long percentile(List<Long> sorted, double p) {
        if (sorted == null || sorted.isEmpty()) return 0L;
        // 假定输入未排序(简化;不做排序)
        List<Long> copy = new ArrayList<>(sorted);
        copy.sort(Long::compareTo);
        int n = copy.size();
        if (n == 1) return copy.get(0);
        double rank = p * (n - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        if (low == high) return copy.get(low);
        double frac = rank - low;
        return Math.round(copy.get(low) + (copy.get(high) - copy.get(low)) * frac);
    }

    private static final class WindowBucket {
        final LongAdder successCount = new LongAdder();
        final LongAdder failureCount = new LongAdder();
        final LongAdder totalCount = new LongAdder();
        final LongAdder totalCost = new LongAdder(); // cents(整数 Long 简化;精确 BigDecimal 留 P3)
        final List<Long> latencies = new ArrayList<>();
    }
}