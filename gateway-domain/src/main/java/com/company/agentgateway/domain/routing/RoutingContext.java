package com.company.agentgateway.domain.routing;

import java.util.Objects;

/**
 * 路由上下文(Round 10):决策时的环境信息。
 *
 * <ul>
 *   <li>{@code tenant} — 租户 ID(spec §6.2);空字符串 = 主租户</li>
 *   <li>{@code promptTokens} — 输入 token 估值(用于 cost 估算)</li>
 *   <li>{@code randomSeed} — WEIGHTED 决策时的随机种子(deterministic 行为)</li>
 * </ul>
 */
public record RoutingContext(String tenant, int promptTokens, long randomSeed) {
    public RoutingContext {
        if (tenant == null) tenant = "primary";
        if (promptTokens < 0) promptTokens = 0;
    }

    public static RoutingContext of(String tenant, int promptTokens, long randomSeed) {
        return new RoutingContext(tenant, promptTokens, randomSeed);
    }

    public static RoutingContext defaults() {
        return new RoutingContext("primary", 0, System.nanoTime());
    }
}