package com.company.agentgateway.domain.routing;

import com.company.agentgateway.domain.shared.ModelId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RouteDecision + RejectedCandidate 测试(Round 10)。
 */
class RouteDecisionTest {

    @Test
    void rejectsNullChosenModel() {
        assertThatThrownBy(() -> new RouteDecision(
                null, "rationale", List.of(), RouteDecision.Source.PRIMARY))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullRationale() {
        assertThatThrownBy(() -> new RouteDecision(
                new ModelId("m1"), null, List.of(), RouteDecision.Source.PRIMARY))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullAlternativesBecomesEmpty() {
        var d = new RouteDecision(new ModelId("m1"), "r", null, RouteDecision.Source.PRIMARY);
        assertThat(d.alternativesConsidered()).isEmpty();
    }

    @Test
    void alternativesCopiedImmutable() {
        var rejected = new ArrayList<>(List.of(
                new RouteDecision.RejectedCandidate("m2", "x")));
        var d = new RouteDecision(new ModelId("m1"), "r", rejected, RouteDecision.Source.PRIMARY);
        assertThatThrownBy(() -> d.alternativesConsidered().add(
                new RouteDecision.RejectedCandidate("m3", "y")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectedCandidateRequiredFields() {
        assertThatThrownBy(() -> new RouteDecision.RejectedCandidate(null, "x"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RouteDecision.RejectedCandidate("m1", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void sourceEnumHasThreeValues() {
        assertThat(RouteDecision.Source.values()).containsExactly(
                RouteDecision.Source.PRIMARY,
                RouteDecision.Source.FALLBACK,
                RouteDecision.Source.DEFAULT
        );
    }
}