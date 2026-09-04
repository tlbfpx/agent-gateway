package com.company.agentgateway.domain.routing;

import com.company.agentgateway.domain.shared.ModelId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 路由指标快照(Round 10):模型级 5min 滑动窗口聚合。
 *
 * <ul>
 *   <li>{@code modelId} — 模型 ID</li>
 *   <li>{@code successRate} — 成功率(0.0 ~ 1.0);sampleCount=0 时 = 0</li>
 *   <li>{@code p50LatencyMs} — p50 首 token 延迟;无样本 = null</li>
 *   <li>{@code avgCostCents} — 平均成本(分/请求);无样本 = null</li>
 *   <li>{@code sampleCount} — 窗口内样本数</li>
 *   <li>{@code windowStart} — 窗口起点(Instant)</li>
 * </ul>
 *
 * <p>域零框架(GW-RT-014):本 record 仅依赖 java.* + domain/shared。
 */
public record RoutingMetricsSnapshot(
        ModelId modelId,
        double successRate,
        Long p50LatencyMs,
        BigDecimal avgCostCents,
        long sampleCount,
        Instant windowStart
) {
    public RoutingMetricsSnapshot {
        Objects.requireNonNull(modelId, "modelId");
        if (successRate < 0.0 || successRate > 1.0) {
            throw new IllegalArgumentException("successRate must be in [0.0, 1.0], got: " + successRate);
        }
        if (sampleCount < 0) {
            throw new IllegalArgumentException("sampleCount must be >= 0");
        }
        if (windowStart == null) {
            windowStart = Instant.EPOCH;
        }
    }

    /** 空白快照(无样本,供 Cold Start 决策使用)。 */
    public static RoutingMetricsSnapshot empty(ModelId modelId) {
        return new RoutingMetricsSnapshot(modelId, 0.0, null, null, 0, Instant.EPOCH);
    }

    public boolean hasSamples() {
        return sampleCount > 0;
    }
}