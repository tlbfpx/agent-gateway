package com.company.agentgateway.domain.routing;

import java.util.List;
import java.util.Objects;

/**
 * 路由策略(Round 10):声明一组候选模型 + 决策目标 + 兜底链路。
 *
 * <ul>
 *   <li>{@code id} — 唯一标识,用于 CRUD + 热更新</li>
 *   <li>{@code strategy} — 主决策策略(4 选 1)</li>
 *   <li>{@code candidates} — 主候选列表(非空)</li>
 *   <li>{@code fallbackChain} — 主候选全部失败/超 budget 时依次尝试的模型 ID 列表</li>
 * </ul>
 */
public record RoutingPolicy(
        String id,
        RoutingStrategy strategy,
        List<Candidate> candidates,
        List<String> fallbackChain
) {
    public RoutingPolicy {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("RoutingPolicy.id must not be blank");
        }
        Objects.requireNonNull(strategy, "strategy");
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("RoutingPolicy.candidates must not be empty");
        }
        if (fallbackChain == null) {
            fallbackChain = List.of();
        }
        // 复制以保证不可变性
        candidates = List.copyOf(candidates);
        fallbackChain = List.copyOf(fallbackChain);
    }

    public static RoutingPolicy of(String id, RoutingStrategy strategy, List<Candidate> candidates) {
        return new RoutingPolicy(id, strategy, candidates, List.of());
    }

    public static RoutingPolicy of(String id, RoutingStrategy strategy, List<Candidate> candidates,
                                    List<String> fallbackChain) {
        return new RoutingPolicy(id, strategy, candidates, fallbackChain);
    }
}