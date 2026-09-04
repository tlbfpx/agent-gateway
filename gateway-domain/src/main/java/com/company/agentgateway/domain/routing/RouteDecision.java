package com.company.agentgateway.domain.routing;

import com.company.agentgateway.domain.shared.ModelId;

import java.util.List;
import java.util.Objects;

/**
 * 路由决策(Round 10):AutoRouter 的最终输出。
 *
 * <ul>
 *   <li>{@code chosenModel} — 选中的模型(必然非 null)</li>
 *   <li>{@code rationale} — 人可读的原因说明(strategy + 选中的指标 + 来源 candidate)</li>
 *   <li>{@code alternativesConsidered} — 被剔除的候选(超 budget 等)+ 原因</li>
 *   <li>{@code source} — 决策来源:"PRIMARY"(主候选选中)/"FALLBACK"(兜底链路)/"DEFAULT"(无候选可用,降级到默认)</li>
 * </ul>
 */
public record RouteDecision(
        ModelId chosenModel,
        String rationale,
        List<RejectedCandidate> alternativesConsidered,
        Source source
) {
    public RouteDecision {
        Objects.requireNonNull(chosenModel, "chosenModel");
        Objects.requireNonNull(rationale, "rationale");
        Objects.requireNonNull(source, "source");
        if (alternativesConsidered == null) {
            alternativesConsidered = List.of();
        }
        alternativesConsidered = List.copyOf(alternativesConsidered);
    }

    public enum Source { PRIMARY, FALLBACK, DEFAULT }

    public record RejectedCandidate(String modelId, String reason) {
        public RejectedCandidate {
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(reason, "reason");
        }
    }
}