package com.company.agentgateway.domain.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RoutingContext 测试(Round 10)。
 */
class RoutingContextTest {

    @Test
    void defaultsFactory() {
        var ctx = RoutingContext.defaults();
        assertThat(ctx.tenant()).isEqualTo("primary");
        assertThat(ctx.promptTokens()).isZero();
        assertThat(ctx.randomSeed()).isNotZero();
    }

    @Test
    void ofFactoryPreservesArgs() {
        var ctx = RoutingContext.of("acme", 100, 42L);
        assertThat(ctx.tenant()).isEqualTo("acme");
        assertThat(ctx.promptTokens()).isEqualTo(100);
        assertThat(ctx.randomSeed()).isEqualTo(42L);
    }

    @Test
    void nullTenantDefaultsToPrimary() {
        var ctx = new RoutingContext(null, 0, 0L);
        assertThat(ctx.tenant()).isEqualTo("primary");
    }

    @Test
    void negativePromptTokensClampedToZero() {
        var ctx = new RoutingContext("t", -5, 0L);
        assertThat(ctx.promptTokens()).isZero();
    }
}