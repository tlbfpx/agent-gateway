package com.company.agentgateway.domain.registry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** RoundRobinEndpointSelector 单测(spec B §4.3)。 */
class RoundRobinEndpointSelectorTest {

    private final RoundRobinEndpointSelector selector = new RoundRobinEndpointSelector();

    private AgentCard card(String name, String... urls) {
        return new AgentCard(name, "d", List.of(), "{}", "{}", "1", true, urls.length > 0 ? urls[0] : null, List.of(urls));
    }

    @Test
    void 旧契约兼容_endpointUrl归一化为endpointUrls() {
        AgentCard c = new AgentCard("a", "d", List.of(), "{}", "{}", "1", true, "http://x");
        assertThat(c.endpointUrls()).containsExactly("http://x");
    }

    @Test
    void endpointUrls显式多实例() {
        AgentCard c = card("a", "u1", "u2", "u3");
        assertThat(c.endpointUrls()).containsExactly("u1", "u2", "u3");
    }

    @Test
    void 轮询在多实例间均匀分配() {
        AgentCard c = card("a", "u1", "u2", "u3");
        // 9 次调用期望每个 url 各 3 次(游标独立,跨测试不污染)
        int[] count = new int[3];
        for (int i = 0; i < 9; i++) {
            String u = selector.select(c);
            count["u1".equals(u) ? 0 : "u2".equals(u) ? 1 : 2]++;
        }
        assertThat(count).containsExactly(3, 3, 3);
    }

    @Test
    void 单实例稳定返回同一url() {
        AgentCard c = card("a", "only");
        assertThat(selector.select(c)).isEqualTo("only");
        assertThat(selector.select(c)).isEqualTo("only");
    }

    @Test
    void 失败后本轮被回避但下轮可恢复() {
        AgentCard c = card("a", "u1", "u2");
        for (int i = 0; i < 3; i++) selector.onFailure("u1");
        // 此时选 u2(被滤掉 u1)
        for (int i = 0; i < 6; i++) {
            assertThat(selector.select(c)).isEqualTo("u2");
        }
        // 标记 u1 成功 → 重置 → 可再次被选中
        selector.onSuccess("u1");
        // 重新轮询,u1 会被选到
        boolean seen = false;
        for (int i = 0; i < 6; i++) {
            if (selector.select(c).equals("u1")) { seen = true; break; }
        }
        assertThat(seen).isTrue();
    }

    @Test
    void null输入安全() {
        assertThat(selector.select(null)).isNull();
        selector.onFailure(null);  // 不抛
        selector.onSuccess(null);
    }
}