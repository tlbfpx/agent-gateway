package com.company.agentgateway.domain.routing;

import java.util.List;

/**
 * 路由端口(Round 10):domain 层抽象接口;infra 层实现。
 *
 * <p>调用方(AutoRouter / ChatOrchestrator)传入 policy + 模型指标快照 + 上下文;
 * 返回 RouteDecision。实现类负责 4 种策略的具体算法 + fallback chain 兜底。
 *
 * <p>异常约定:
 * <ul>
 *   <li>候选全部超 budget 且 fallbackChain 也耗尽 → 抛 {@link RoutingPolicyExhaustedException}</li>
 *   <li>候选全部为空 → 抛 {@link IllegalArgumentException}</li>
 * </ul>
 */
public interface RoutingPort {

    /**
     * @param policy 决策策略(必填,非 null)
     * @param candidateMetrics 每个候选模型的指标快照;可空 = 无历史,走冷启动
     * @param ctx 路由上下文
     * @return 路由决策(chosenModel 必非 null)
     * @throws RoutingPolicyExhaustedException 候选 + fallbackChain 全部耗尽
     */
    RouteDecision decide(RoutingPolicy policy,
                          List<RoutingMetricsSnapshot> candidateMetrics,
                          RoutingContext ctx);
}