package com.company.agentgateway.domain.routing;

import com.company.agentgateway.domain.shared.ModelId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RoutingMetricsPort 默认方法 snapshotOne 测试(Round 10)。
 */
class RoutingMetricsPortTest {

    @Test
    void snapshotOneReturnsFirstOrEmpty() {
        var m1 = new ModelId("m1");
        var m2 = new ModelId("m2");
        var snap = new RoutingMetricsSnapshot(m1, 0.9, 100L, java.math.BigDecimal.ONE,
                10, java.time.Instant.now());
        RoutingMetricsPort port = ids -> ids.contains(m1) ? List.of(snap) : List.of();

        var result = port.snapshotOne(m1);
        assertThat(result.modelId()).isEqualTo(m1);
        assertThat(result.sampleCount()).isEqualTo(10);

        var empty = port.snapshotOne(m2);
        assertThat(empty.hasSamples()).isFalse();
        assertThat(empty.modelId()).isEqualTo(m2);
    }
}