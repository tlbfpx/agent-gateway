package com.company.agentgateway.application.routing;

import com.company.agentgateway.domain.routing.RoutingContext;
import com.company.agentgateway.domain.routing.RoutingMetricsPort;
import com.company.agentgateway.domain.routing.RoutingMetricsSnapshot;
import com.company.agentgateway.domain.routing.RoutingPolicy;
import com.company.agentgateway.domain.routing.RoutingPort;
import com.company.agentgateway.domain.routing.RouteDecision;
import com.company.agentgateway.domain.shared.ModelId;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Auto Router 用例(Round 10):编排 RoutingPort + RoutingMetricsPort + 策略存储。
 *
 * <p>输入:{@link RoutingContext}(环境信息)
 * <br>输出:{@link RouteDecision}(chosenModel + rationale + 被剔除候选)
 *
 * <h2>装配</h2>
 * Spring 4.0 严格模式下,AutoRouter 在 {@code gateway.routing.enabled=true} 时装配。
 * {@link ChatOrchestrator} 通过 Optional 注入;无 AutoRouter 时维持原硬选逻辑(GW-RT-012)。
 *
 * <h2>应用层零 infra 依赖</h2>
 * 仅依赖 domain/{@link RoutingPort},{@link RoutingMetricsPort} 接口 + 业务模型;
 * 具体 Micrometer / Caffeine 实现位于 {@code gateway-infra-llm/routing}。
 */
public final class AutoRouter {

    private final RoutingPort routingPort;
    private final RoutingMetricsPort metricsPort;
    private final Consumer<RoutingPolicy> policySink;

    public AutoRouter(RoutingPort routingPort, RoutingMetricsPort metricsPort) {
        this(routingPort, metricsPort, policy -> { /* 默认丢弃 */ });
    }

    public AutoRouter(RoutingPort routingPort, RoutingMetricsPort metricsPort,
                      Consumer<RoutingPolicy> policySink) {
        this.routingPort = Objects.requireNonNull(routingPort, "routingPort");
        this.metricsPort = Objects.requireNonNull(metricsPort, "metricsPort");
        this.policySink = Objects.requireNonNull(policySink, "policySink");
    }

    /**
     * 决策:取策略 → 查候选指标 → 委托 RoutingPort。
     *
     * @throws com.company.agentgateway.domain.routing.RoutingPolicyExhaustedException 候选 + fallback 耗尽
     */
    public RouteDecision decide(RoutingPolicy policy, RoutingContext ctx) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(ctx, "ctx");
        // 1. 列出候选模型 ID
        List<ModelId> modelIds = policy.candidates().stream()
                .map(c -> c.modelId())
                .toList();
        // 2. 查询每个候选的指标快照
        List<RoutingMetricsSnapshot> snapshots = metricsPort.snapshot(modelIds);
        // 3. 委托 RoutingPort 做实际决策
        RouteDecision decision = routingPort.decide(policy, snapshots, ctx);
        // 4. 上报策略使用(policySink 用于 audit / metrics 接入,留 Round 10 后续)
        policySink.accept(policy);
        return decision;
    }
}