package com.company.agentgateway.domain.routing;

import com.company.agentgateway.domain.shared.ModelId;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 路由候选模型(Round 10):
 *
 * <ul>
 *   <li>{@code modelId} — 候选模型 ID</li>
 *   <li>{@code weight} — WEIGHTED 策略时的相对权重;其他策略忽略(但仍需 > 0)</li>
 *   <li>{@code costCeilingCents} — 平均成本上限(分/请求);null = 不限</li>
 *   <li>{@code latencyP99CeilingMs} — p99 延迟上限(毫秒);null = 不限</li>
 * </ul>
 *
 * <p>任一 ceiling 非 null 时,超 ceiling 的候选在决策时被过滤掉。
 */
public record Candidate(
        ModelId modelId,
        int weight,
        BigDecimal costCeilingCents,
        Long latencyP99CeilingMs
) {
    public Candidate {
        Objects.requireNonNull(modelId, "modelId");
        if (weight <= 0) {
            throw new IllegalArgumentException("Candidate.weight must be > 0, got: " + weight);
        }
        if (costCeilingCents != null && costCeilingCents.signum() < 0) {
            throw new IllegalArgumentException("costCeilingCents must be >= 0");
        }
        if (latencyP99CeilingMs != null && latencyP99CeilingMs < 0) {
            throw new IllegalArgumentException("latencyP99CeilingMs must be >= 0");
        }
    }

    /** 简化构造:weight=1,无 ceiling。 */
    public static Candidate of(ModelId modelId) {
        return new Candidate(modelId, 1, null, null);
    }

    /** 自定义 weight + ceiling。 */
    public static Candidate of(ModelId modelId, int weight,
                                BigDecimal costCeilingCents, Long latencyP99CeilingMs) {
        return new Candidate(modelId, weight, costCeilingCents, latencyP99CeilingMs);
    }
}