package com.company.agentgateway.infra.llm.routing;

import com.company.agentgateway.domain.routing.Candidate;
import com.company.agentgateway.domain.routing.RouteDecision;
import com.company.agentgateway.domain.routing.RoutingContext;
import com.company.agentgateway.domain.routing.RoutingMetricsSnapshot;
import com.company.agentgateway.domain.routing.RoutingPolicy;
import com.company.agentgateway.domain.routing.RoutingPolicyExhaustedException;
import com.company.agentgateway.domain.routing.RoutingStrategy;
import com.company.agentgateway.domain.shared.ModelId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DefaultRoutingService 单元测试(Round 10):验证 GW-RT-004 ~ GW-RT-010 算法实现。
 */
class DefaultRoutingServiceTest {

    private static final ModelId CHEAP = new ModelId("cheap");
    private static final ModelId FAST = new ModelId("fast");
    private static final ModelId QUALITY = new ModelId("quality");
    private static final ModelId EXPENSIVE = new ModelId("expensive");

    private final DefaultRoutingService service = new DefaultRoutingService();

    @Test
    @DisplayName("GW-RT-004:LOWEST_COST → 选 avgCostCents 最小的候选")
    void lowestCostPicksCheapest() {
        var snapshots = List.of(
                snap(CHEAP, 0.9, 200L, new BigDecimal("0.10"), 100),
                snap(FAST, 0.95, 50L, new BigDecimal("0.50"), 100),
                snap(EXPENSIVE, 0.99, 100L, new BigDecimal("1.00"), 100)
        );
        var policy = new RoutingPolicy("p", RoutingStrategy.LOWEST_COST,
                List.of(Candidate.of(CHEAP), Candidate.of(FAST), Candidate.of(EXPENSIVE)),
                List.of());

        var decision = service.decide(policy, snapshots, RoutingContext.defaults());

        assertThat(decision.chosenModel()).isEqualTo(CHEAP);
        assertThat(decision.rationale()).startsWith("LOWEST_COST:");
        assertThat(decision.rationale()).contains("avgCost=0.10");
        assertThat(decision.source()).isEqualTo(RouteDecision.Source.PRIMARY);
    }

    @Test
    @DisplayName("GW-RT-005:FASTEST_FIRST_TOKEN → 选 p50LatencyMs 最小的候选")
    void fastestPicksLowestLatency() {
        var snapshots = List.of(
                snap(CHEAP, 0.9, 200L, new BigDecimal("0.10"), 100),
                snap(FAST, 0.95, 50L, new BigDecimal("0.50"), 100)
        );
        var policy = new RoutingPolicy("p", RoutingStrategy.FASTEST_FIRST_TOKEN,
                List.of(Candidate.of(CHEAP), Candidate.of(FAST)), List.of());

        var decision = service.decide(policy, snapshots, RoutingContext.defaults());

        assertThat(decision.chosenModel()).isEqualTo(FAST);
        assertThat(decision.rationale()).contains("p50=50ms");
    }

    @Test
    @DisplayName("GW-RT-006:QUALITY_FIRST → 选 successRate × 置信度 最高的候选")
    void qualityPicksHighest() {
        var snapshots = List.of(
                snap(CHEAP, 0.50, 200L, new BigDecimal("0.10"), 50),   // 0.50 × 0.5 = 0.25
                snap(QUALITY, 0.95, 300L, new BigDecimal("1.00"), 200) // 0.95 × 1.0 = 0.95
        );
        var policy = new RoutingPolicy("p", RoutingStrategy.QUALITY_FIRST,
                List.of(Candidate.of(CHEAP), Candidate.of(QUALITY)), List.of());

        var decision = service.decide(policy, snapshots, RoutingContext.defaults());

        assertThat(decision.chosenModel()).isEqualTo(QUALITY);
        assertThat(decision.rationale()).startsWith("QUALITY_FIRST:");
    }

    @Test
    @DisplayName("GW-RT-007:WEIGHTED → 按 weight 比例随机(deterministic seed)")
    void weightedPicksByWeights() {
        var policy = new RoutingPolicy("p", RoutingStrategy.WEIGHTED,
                List.of(Candidate.of(CHEAP, 9, null, null),
                        Candidate.of(FAST, 1, null, null)),
                List.of());
        // seed=42 → nextDouble() 大概率落在 [0, 0.9),选 CHEAP
        var ctx = new RoutingContext("primary", 0, 42L);
        var decision = service.decide(policy, java.util.Collections.<RoutingMetricsSnapshot>emptyList(), ctx);

        assertThat(decision.chosenModel()).isEqualTo(CHEAP);
        assertThat(decision.rationale()).contains("WEIGHTED:");
    }

    @Test
    @DisplayName("GW-RT-007:WEIGHTED 给定不同 seed 产生不同结果")
    void weightedDistributionMatchesWeights() {
        var policy = new RoutingPolicy("p", RoutingStrategy.WEIGHTED,
                List.of(Candidate.of(CHEAP, 1, null, null),
                        Candidate.of(FAST, 1, null, null)),
                List.of());
        // 多次不同 seed,统计分布
        int cheapCount = 0;
        int fastCount = 0;
        for (long seed = 1; seed <= 200; seed++) {
            var ctx = new RoutingContext("primary", 0, seed);
            var d = service.decide(policy, java.util.Collections.<RoutingMetricsSnapshot>emptyList(), ctx);
            if (d.chosenModel().equals(CHEAP)) cheapCount++;
            else if (d.chosenModel().equals(FAST)) fastCount++;
        }
        // 期望近似 50/50 分布(允许 60/40 误差)
        assertThat(cheapCount).isBetween(70, 130);
        assertThat(fastCount).isBetween(70, 130);
    }

    @Test
    @DisplayName("GW-RT-008:全部候选超 budget → fallbackChain 兜底")
    void allCandidatesOverBudgetTriggersFallback() {
        var snapshots = List.of(
                snap(CHEAP, 0.9, 200L, new BigDecimal("5.00"), 100), // > 0.10 ceiling
                snap(FAST, 0.95, 5000L, new BigDecimal("0.50"), 100)  // > 1000ms ceiling
        );
        var policy = new RoutingPolicy("p", RoutingStrategy.LOWEST_COST,
                List.of(Candidate.of(CHEAP, 1, new BigDecimal("0.10"), null),
                        Candidate.of(FAST, 1, null, 1000L)),
                List.of("gpt-4o"));

        var decision = service.decide(policy, snapshots, RoutingContext.defaults());

        assertThat(decision.chosenModel().value()).isEqualTo("gpt-4o");
        assertThat(decision.source()).isEqualTo(RouteDecision.Source.FALLBACK);
        assertThat(decision.alternativesConsidered()).hasSize(2);
    }

    @Test
    @DisplayName("GW-RT-008:fallbackChain 走完仍失败 → RoutingPolicyExhaustedException")
    void fallbackChainExhaustedThrows() {
        var snapshots = List.of(
                snap(CHEAP, 0.9, 200L, new BigDecimal("5.00"), 100)
        );
        var policy = new RoutingPolicy("p", RoutingStrategy.LOWEST_COST,
                List.of(Candidate.of(CHEAP, 1, new BigDecimal("0.10"), null)),
                List.of());

        assertThatThrownBy(() -> service.decide(policy, snapshots, RoutingContext.defaults()))
                .isInstanceOf(RoutingPolicyExhaustedException.class)
                .hasMessageContaining("exhausted");
    }

    @Test
    @DisplayName("GW-RT-009:超 ceiling 候选在决策前被剔除(出现在 alternativesConsidered)")
    void overBudgetCandidateFiltered() {
        var snapshots = List.of(
                snap(CHEAP, 0.9, 200L, new BigDecimal("0.10"), 100),  // pass
                snap(EXPENSIVE, 0.99, 100L, new BigDecimal("2.00"), 100) // > 0.50 ceiling
        );
        var policy = new RoutingPolicy("p", RoutingStrategy.LOWEST_COST,
                List.of(Candidate.of(CHEAP, 1, new BigDecimal("0.50"), null),
                        Candidate.of(EXPENSIVE, 1, new BigDecimal("0.50"), null)),
                List.of());

        var decision = service.decide(policy, snapshots, RoutingContext.defaults());

        assertThat(decision.chosenModel()).isEqualTo(CHEAP);
        assertThat(decision.alternativesConsidered()).hasSize(1);
        assertThat(decision.alternativesConsidered().get(0).modelId()).isEqualTo(EXPENSIVE.value());
        assertThat(decision.alternativesConsidered().get(0).reason()).contains("avgCost");
    }

    @Test
    @DisplayName("GW-RT-010:RouteDecision.rationale 含 strategy + 选中指标值")
    void rationaleContainsStrategyAndMetrics() {
        var snapshots = List.of(snap(CHEAP, 0.9, 200L, new BigDecimal("0.10"), 100));
        var policy = new RoutingPolicy("p", RoutingStrategy.LOWEST_COST,
                List.of(Candidate.of(CHEAP)), List.of());

        var decision = service.decide(policy, snapshots, RoutingContext.defaults());

        assertThat(decision.rationale()).contains("LOWEST_COST");
        assertThat(decision.rationale()).contains("avgCost=0.10");
    }

    @Test
    @DisplayName("Cold Start 无 samples → LOWEST_COST 按 costCeiling 选")
    void coldStartLowestCostByCeiling() {
        var snapshots = java.util.Collections.<RoutingMetricsSnapshot>emptyList(); // 无指标
        var policy = new RoutingPolicy("p", RoutingStrategy.LOWEST_COST,
                List.of(Candidate.of(CHEAP, 1, new BigDecimal("0.20"), null),
                        Candidate.of(FAST, 1, new BigDecimal("0.10"), null)),
                List.of());

        var decision = service.decide(policy, snapshots, RoutingContext.defaults());

        // 无指标 → 按 costCeiling 升序选 FAST(0.10 < 0.20)
        assertThat(decision.chosenModel()).isEqualTo(FAST);
        assertThat(decision.rationale()).contains("cold start");
    }

    private static RoutingMetricsSnapshot snap(ModelId m, double rate, long p50,
                                                BigDecimal cost, long samples) {
        return new RoutingMetricsSnapshot(m, rate, p50, cost, samples, Instant.now());
    }
}