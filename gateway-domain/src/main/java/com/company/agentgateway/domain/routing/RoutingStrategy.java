package com.company.agentgateway.domain.routing;

/**
 * 路由策略枚举(Round 10):决定 AutoRouter 如何从候选模型中挑选。
 *
 * <ul>
 *   <li>{@link #LOWEST_COST} — 选 avgCostCents 最小</li>
 *   <li>{@link #FASTEST_FIRST_TOKEN} — 选 p50LatencyMs 最小</li>
 *   <li>{@link #QUALITY_FIRST} — 选 successRate × 置信度 最高</li>
 *   <li>{@link #WEIGHTED} — 按 Candidate.weight 比例随机(确定性 seed)</li>
 * </ul>
 */
public enum RoutingStrategy {
    LOWEST_COST,
    FASTEST_FIRST_TOKEN,
    QUALITY_FIRST,
    WEIGHTED
}