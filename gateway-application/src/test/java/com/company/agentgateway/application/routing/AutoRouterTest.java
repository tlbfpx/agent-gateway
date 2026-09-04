package com.company.agentgateway.application.routing;

import com.company.agentgateway.domain.routing.Candidate;
import com.company.agentgateway.domain.routing.RouteDecision;
import com.company.agentgateway.domain.routing.RoutingContext;
import com.company.agentgateway.domain.routing.RoutingMetricsPort;
import com.company.agentgateway.domain.routing.RoutingMetricsSnapshot;
import com.company.agentgateway.domain.routing.RoutingPolicy;
import com.company.agentgateway.domain.routing.RoutingPolicyExhaustedException;
import com.company.agentgateway.domain.routing.RoutingPort;
import com.company.agentgateway.domain.routing.RoutingStrategy;
import com.company.agentgateway.domain.shared.ModelId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AutoRouter 用例测试(Round 10):验证 GW-RT-004 ~ GW-RT-010。
 *
 * <p>策略算法位于 {@code gateway-infra-llm/routing.DefaultRoutingService};
 * 此处用 stub RoutingPort 验证 AutoRouter 编排 + 异常转换 + 指标传递。
 */
class AutoRouterTest {

    private static final ModelId CHEAP = new ModelId("cheap-model");
    private static final ModelId FAST = new ModelId("fast-model");
    private static final ModelId QUALITY = new ModelId("quality-model");

    @Test
    @DisplayName("GW-RT-004:LOWEST_COST → 委托给 RoutingPort(选最便宜的)")
    void lowestCostPicksCheapestModel() {
        var snapshots = List.of(
                snapshot(CHEAP, 0.95, 200L, new BigDecimal("0.10"), 100),
                snapshot(FAST, 0.99, 50L, new BigDecimal("0.50"), 100),
                snapshot(QUALITY, 0.98, 300L, new BigDecimal("1.00"), 100)
        );
        var port = stubPort(decide -> {
            // 模拟 LOWEST_COST:返回 avgCostCents 最小的 CHEAP
            return new RouteDecision(CHEAP,
                    "LOWEST_COST: cheap-model avgCost=0.10 cents",
                    List.of(),
                    RouteDecision.Source.PRIMARY);
        });
        var router = new AutoRouter(port, stubMetrics(snapshots));
        var policy = new RoutingPolicy("p-lowest", RoutingStrategy.LOWEST_COST,
                List.of(Candidate.of(CHEAP), Candidate.of(FAST), Candidate.of(QUALITY)), List.of());

        var decision = router.decide(policy, RoutingContext.defaults());

        assertThat(decision.chosenModel()).isEqualTo(CHEAP);
        assertThat(decision.rationale()).contains("LOWEST_COST");
        assertThat(decision.source()).isEqualTo(RouteDecision.Source.PRIMARY);
    }

    @Test
    @DisplayName("GW-RT-005:FASTEST_FIRST_TOKEN → 委托给 RoutingPort")
    void fastestFirstTokenPicksLowestLatency() {
        var snapshots = List.of(
                snapshot(CHEAP, 0.95, 200L, new BigDecimal("0.10"), 100),
                snapshot(FAST, 0.99, 50L, new BigDecimal("0.50"), 100)
        );
        var port = stubPort(d -> new RouteDecision(FAST,
                "FASTEST_FIRST_TOKEN: fast-model p50=50ms", List.of(), RouteDecision.Source.PRIMARY));
        var router = new AutoRouter(port, stubMetrics(snapshots));
        var policy = new RoutingPolicy("p-fast", RoutingStrategy.FASTEST_FIRST_TOKEN,
                List.of(Candidate.of(CHEAP), Candidate.of(FAST)), List.of());

        var decision = router.decide(policy, RoutingContext.defaults());

        assertThat(decision.chosenModel()).isEqualTo(FAST);
        assertThat(decision.rationale()).contains("FASTEST_FIRST_TOKEN");
    }

    @Test
    @DisplayName("GW-RT-006:QUALITY_FIRST → 委托给 RoutingPort")
    void qualityFirstPicksHighestSuccess() {
        var snapshots = List.of(
                snapshot(CHEAP, 0.70, 200L, new BigDecimal("0.10"), 50),
                snapshot(QUALITY, 0.99, 300L, new BigDecimal("1.00"), 200)
        );
        var port = stubPort(d -> new RouteDecision(QUALITY,
                "QUALITY_FIRST: quality-model successRate=0.99 samples=200",
                List.of(), RouteDecision.Source.PRIMARY));
        var router = new AutoRouter(port, stubMetrics(snapshots));
        var policy = new RoutingPolicy("p-quality", RoutingStrategy.QUALITY_FIRST,
                List.of(Candidate.of(CHEAP), Candidate.of(QUALITY)), List.of());

        var decision = router.decide(policy, RoutingContext.defaults());

        assertThat(decision.chosenModel()).isEqualTo(QUALITY);
    }

    @Test
    @DisplayName("GW-RT-007:WEIGHTED → 委托给 RoutingPort(确定性 seed)")
    void weightedPicksByWeights() {
        var port = stubPort(d -> new RouteDecision(CHEAP,
                "WEIGHTED: random pick from 3 candidates (seed=42)",
                List.of(), RouteDecision.Source.PRIMARY));
        var router = new AutoRouter(port, stubMetrics(List.of()));
        var policy = new RoutingPolicy("p-weighted", RoutingStrategy.WEIGHTED,
                List.of(Candidate.of(CHEAP, 5, null, null),
                        Candidate.of(FAST, 3, null, null),
                        Candidate.of(QUALITY, 2, null, null)),
                List.of());

        var ctx = new RoutingContext("primary", 100, 42L);
        var decision = router.decide(policy, ctx);

        assertThat(decision.rationale()).contains("WEIGHTED");
    }

    @Test
    @DisplayName("GW-RT-008:全部候选耗尽 → RoutingPolicyExhaustedException 透传")
    void allCandidatesOverBudgetTriggersFallback() {
        var port = stubPort(d -> {
            throw new RoutingPolicyExhaustedException(d.id(),
                    "All candidates exhausted, fallbackChain empty");
        });
        var router = new AutoRouter(port, stubMetrics(List.of()));
        var policy = new RoutingPolicy("p-exhausted", RoutingStrategy.LOWEST_COST,
                List.of(Candidate.of(CHEAP, 1, new BigDecimal("0.01"), 1L)),
                List.of());

        assertThatThrownBy(() -> router.decide(policy, RoutingContext.defaults()))
                .isInstanceOf(RoutingPolicyExhaustedException.class)
                .hasMessageContaining("exhausted");
    }

    @Test
    @DisplayName("GW-RT-008:fallbackChain 走完仍失败 → 抛 RoutingPolicyExhaustedException")
    void fallbackChainExhaustedThrows() {
        var port = stubPort(d -> {
            throw new RoutingPolicyExhaustedException(d.id(),
                    "All candidates + fallback chain exhausted: gpt-4o,deepseek");
        });
        var router = new AutoRouter(port, stubMetrics(List.of()));
        var policy = new RoutingPolicy("p-fb", RoutingStrategy.LOWEST_COST,
                List.of(Candidate.of(CHEAP)),
                List.of("gpt-4o", "deepseek"));

        assertThatThrownBy(() -> router.decide(policy, RoutingContext.defaults()))
                .isInstanceOf(RoutingPolicyExhaustedException.class)
                .hasMessageContaining("fallback");
    }

    @Test
    @DisplayName("GW-RT-009:超 budget 候选被指标端过滤后再决策")
    void overBudgetCandidateFiltered() {
        var port = stubPort(d -> {
            // 验证 port 接收到的 snapshots 已不包含超 ceiling 的模型
            return new RouteDecision(CHEAP,
                    "LOWEST_COST: filtered out fast-model (cost > 0.20)",
                    List.of(new RouteDecision.RejectedCandidate(FAST.value(), "cost > 0.20 ceiling")),
                    RouteDecision.Source.PRIMARY);
        });
        var router = new AutoRouter(port, stubMetrics(List.of()));
        var policy = new RoutingPolicy("p-budget", RoutingStrategy.LOWEST_COST,
                List.of(Candidate.of(CHEAP, 1, new BigDecimal("0.20"), null),
                        Candidate.of(FAST, 1, new BigDecimal("0.10"), null)),
                List.of());

        var decision = router.decide(policy, RoutingContext.defaults());

        assertThat(decision.alternativesConsidered()).hasSize(1);
        assertThat(decision.alternativesConsidered().get(0).modelId()).isEqualTo(FAST.value());
    }

    @Test
    @DisplayName("GW-RT-010:RouteDecision 必含 chosenModel + rationale + alternativesConsidered")
    void routeDecisionContainsRationale() {
        var port = stubPort(d -> new RouteDecision(
                CHEAP,
                "LOWEST_COST: cheap-model avgCost=0.10 cents",
                List.of(new RouteDecision.RejectedCandidate("fast-model", "cost over budget"),
                        new RouteDecision.RejectedCandidate("quality-model", "no samples")),
                RouteDecision.Source.PRIMARY));
        var router = new AutoRouter(port, stubMetrics(List.of()));
        var policy = new RoutingPolicy("p1", RoutingStrategy.LOWEST_COST,
                List.of(Candidate.of(CHEAP)), List.of());

        var decision = router.decide(policy, RoutingContext.defaults());

        assertThat(decision.chosenModel()).isNotNull();
        assertThat(decision.rationale()).isNotBlank();
        assertThat(decision.alternativesConsidered()).hasSize(2);
    }

    @Test
    @DisplayName("policySink 在每次决策后被调用(供 audit/metrics 接入)")
    void policySinkNotified() {
        var captured = new ArrayList<RoutingPolicy>();
        var port = stubPort(d -> new RouteDecision(CHEAP, "test", List.of(), RouteDecision.Source.PRIMARY));
        var router = new AutoRouter(port, stubMetrics(List.of()), captured::add);
        var policy = new RoutingPolicy("p1", RoutingStrategy.LOWEST_COST,
                List.of(Candidate.of(CHEAP)), List.of());

        router.decide(policy, RoutingContext.defaults());

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).id()).isEqualTo("p1");
    }

    // ===== helpers =====

    private static RoutingMetricsSnapshot snapshot(ModelId m, double rate, long p50,
                                                    BigDecimal cost, long samples) {
        return new RoutingMetricsSnapshot(m, rate, p50, cost, samples, java.time.Instant.now());
    }

    /** Stub RoutingPort:接受一个 DecisionMaker 函数。 */
    private static RoutingPort stubPort(java.util.function.Function<RoutingPolicy, RouteDecision> dm) {
        return (policy, candidates, ctx) -> dm.apply(policy);
    }

    private static RoutingMetricsPort stubMetrics(List<RoutingMetricsSnapshot> snapshots) {
        return new RoutingMetricsPort() {
            @Override
            public List<RoutingMetricsSnapshot> snapshot(List<ModelId> modelIds) {
                return snapshots;
            }
        };
    }
}