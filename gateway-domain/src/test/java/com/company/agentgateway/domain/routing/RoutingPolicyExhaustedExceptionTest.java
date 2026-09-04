package com.company.agentgateway.domain.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RoutingPolicyExhaustedException 测试(Round 10)。
 */
class RoutingPolicyExhaustedExceptionTest {

    @Test
    void carriesPolicyIdAndMessage() {
        var e = new RoutingPolicyExhaustedException("p1", "all candidates over budget");
        assertThat(e.policyId()).isEqualTo("p1");
        assertThat(e.getMessage()).isEqualTo("all candidates over budget");
    }
}