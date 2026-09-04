package com.company.agentgateway.domain.routing;

import com.company.agentgateway.domain.shared.ModelId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Candidate 校验测试(Round 10)。
 */
class CandidateTest {

    @Test
    void rejectsNegativeWeight() {
        assertThatThrownBy(() -> new Candidate(new ModelId("m1"), -1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeCostCeiling() {
        assertThatThrownBy(() -> new Candidate(new ModelId("m1"), 1, new BigDecimal("-1.0"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeLatencyCeiling() {
        assertThatThrownBy(() -> new Candidate(new ModelId("m1"), 1, null, -100L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsNullCeilings() {
        var c = new Candidate(new ModelId("m1"), 1, null, null);
        assertThat(c.costCeilingCents()).isNull();
        assertThat(c.latencyP99CeilingMs()).isNull();
    }

    @Test
    void staticFactoryOfDefaults() {
        var c = Candidate.of(new ModelId("m1"));
        assertThat(c.weight()).isEqualTo(1);
        assertThat(c.costCeilingCents()).isNull();
    }
}