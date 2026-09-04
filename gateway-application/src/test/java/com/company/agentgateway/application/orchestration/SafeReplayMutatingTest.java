package com.company.agentgateway.application.orchestration;

import com.company.agentgateway.domain.registry.AgentCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mutating 工具识别(Sprint 2 P1 §3.1)最小可测点:
 * AgentCard.mutating 字段能正确传递与默认值正确。
 * 完整 ChatOrchestrator.executeToolCalls 行为测试需要大量 stub,留作完整 Playwright E2E 覆盖。
 */
class SafeReplayMutatingTest {

    @Test
    @DisplayName("AgentCard.mutating=true 创建后正确读取")
    void mutatingTrue() {
        AgentCard card = new AgentCard(
                "write-agent", "writes", List.of(), "{}", "{}",
                "1.0", true, "http://x", List.of("http://x"), /* mutating */ true);
        assertThat(card.mutating()).isTrue();
    }

    @Test
    @DisplayName("AgentCard.mutating=false 默认行为")
    void mutatingDefaultFalse() {
        AgentCard card = new AgentCard(
                "read-agent", "reads", List.of(), "{}", "{}",
                "1.0", true, "http://x");
        assertThat(card.mutating()).isFalse();

        // 9-arg 兼容构造也默认 false
        AgentCard card2 = new AgentCard(
                "read-agent", "reads", List.of(), "{}", "{}",
                "1.0", true, "http://x", List.of("http://x"));
        assertThat(card2.mutating()).isFalse();
    }

    @Test
    @DisplayName("AgentCard 9 参 + mutating=true 显式构造")
    void mutatingExplicit() {
        AgentCard card = new AgentCard(
                "agent", "desc", List.of("skill"), "{}", "{}",
                "1.0", true, "http://x", List.of("http://x"), true);
        assertThat(card.mutating()).isTrue();
        assertThat(card.skills()).containsExactly("skill");
    }
}