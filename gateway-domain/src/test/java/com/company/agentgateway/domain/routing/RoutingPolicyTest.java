package com.company.agentgateway.domain.routing;

import com.company.agentgateway.domain.shared.ModelId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RoutingPolicy 校验测试(Round 10):验证 GW-RT-003。
 */
class RoutingPolicyTest {

    @Test
    @DisplayName("GW-RT-003:空 candidates → IllegalArgumentException")
    void emptyCandidatesRejected() {
        assertThatThrownBy(() ->
                new RoutingPolicy("p1", RoutingStrategy.LOWEST_COST, List.of(), List.of())
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("candidates must not be empty");
    }

    @Test
    @DisplayName("GW-RT-003:negative weight → IllegalArgumentException")
    void negativeWeightRejected() {
        // Candidate 构造器直接拒绝(weight 必须 > 0)
        assertThatThrownBy(() ->
                new Candidate(new ModelId("gpt-4o"), -1, null, null)
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("weight");
    }

    @Test
    @DisplayName("GW-RT-003:zero weight → IllegalArgumentException")
    void zeroWeightRejected() {
        // Candidate 构造器直接拒绝
        assertThatThrownBy(() ->
                new Candidate(new ModelId("gpt-4o"), 0, null, null)
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("weight");
    }

    @Test
    @DisplayName("GW-RT-003:正常构造 + candidates 不可变副本")
    void normalConstruction() {
        var c1 = new Candidate(new ModelId("gpt-4o"), 1, new BigDecimal("0.5"), 1000L);
        var c2 = new Candidate(new ModelId("deepseek-v3"), 2, null, 800L);
        var policy = new RoutingPolicy("p1", RoutingStrategy.LOWEST_COST, List.of(c1, c2), List.of("fallback"));

        assertThat(policy.id()).isEqualTo("p1");
        assertThat(policy.strategy()).isEqualTo(RoutingStrategy.LOWEST_COST);
        assertThat(policy.candidates()).hasSize(2);
        assertThat(policy.fallbackChain()).containsExactly("fallback");

        // candidates 不可变
        assertThatThrownBy(() -> policy.candidates().add(c1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("GW-RT-003:null fallbackChain → 默认为空列表")
    void nullFallbackDefaultsToEmpty() {
        var c1 = Candidate.of(new ModelId("gpt-4o"));
        var policy = new RoutingPolicy("p1", RoutingStrategy.LOWEST_COST, List.of(c1), null);
        assertThat(policy.fallbackChain()).isEmpty();
    }
}