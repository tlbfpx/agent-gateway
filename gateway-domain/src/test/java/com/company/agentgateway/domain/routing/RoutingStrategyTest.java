package com.company.agentgateway.domain.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RoutingStrategy 枚举完整性测试(Round 10):验证 4 个值 + 序列化名。
 */
class RoutingStrategyTest {

    @Test
    @DisplayName("GW-RT-002:枚举含 4 个标准值")
    void allValuesPresent() {
        assertThat(RoutingStrategy.values()).containsExactly(
                RoutingStrategy.LOWEST_COST,
                RoutingStrategy.FASTEST_FIRST_TOKEN,
                RoutingStrategy.QUALITY_FIRST,
                RoutingStrategy.WEIGHTED
        );
    }

    @Test
    @DisplayName("GW-RT-002:枚举 valueOf 与 name 一致")
    void valueOfMatchesName() {
        assertThat(RoutingStrategy.valueOf("LOWEST_COST")).isEqualTo(RoutingStrategy.LOWEST_COST);
        assertThat(RoutingStrategy.valueOf("FASTEST_FIRST_TOKEN")).isEqualTo(RoutingStrategy.FASTEST_FIRST_TOKEN);
        assertThat(RoutingStrategy.valueOf("QUALITY_FIRST")).isEqualTo(RoutingStrategy.QUALITY_FIRST);
        assertThat(RoutingStrategy.valueOf("WEIGHTED")).isEqualTo(RoutingStrategy.WEIGHTED);
    }
}