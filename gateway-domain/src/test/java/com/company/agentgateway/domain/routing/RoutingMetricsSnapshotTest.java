package com.company.agentgateway.domain.routing;

import com.company.agentgateway.domain.shared.ModelId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RoutingMetricsSnapshot 校验测试(Round 10)。
 */
class RoutingMetricsSnapshotTest {

    @Test
    void rejectsSuccessRateAboveOne() {
        assertThatThrownBy(() -> new RoutingMetricsSnapshot(
                new ModelId("m1"), 1.5, 100L, BigDecimal.ONE, 10, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeSuccessRate() {
        assertThatThrownBy(() -> new RoutingMetricsSnapshot(
                new ModelId("m1"), -0.1, 100L, BigDecimal.ONE, 10, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeSampleCount() {
        assertThatThrownBy(() -> new RoutingMetricsSnapshot(
                new ModelId("m1"), 0.5, 100L, BigDecimal.ONE, -1, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyFactoryHasNoSamples() {
        var s = RoutingMetricsSnapshot.empty(new ModelId("m1"));
        assertThat(s.hasSamples()).isFalse();
        assertThat(s.successRate()).isZero();
        assertThat(s.sampleCount()).isZero();
    }

    @Test
    void hasSamplesReflectsCount() {
        var s = new RoutingMetricsSnapshot(new ModelId("m1"), 0.95, 200L,
                new BigDecimal("0.3"), 100, Instant.now());
        assertThat(s.hasSamples()).isTrue();
        assertThat(s.successRate()).isEqualTo(0.95);
    }
}