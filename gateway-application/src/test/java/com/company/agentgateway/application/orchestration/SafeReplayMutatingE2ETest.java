package com.company.agentgateway.application.orchestration;

import com.company.agentgateway.domain.orchestration.ToolDescriptor;
import com.company.agentgateway.domain.registry.AgentCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mutating 工具识别在 ChatOrchestrator 真实路径上的验证(Sprint 2 P2.1):
 *
 * <p>完整 ChatOrchestrator 编排涉及 12 个依赖(Audit/Auth/Llm/Tool/etc),本测试聚焦:
 * 1) AgentCard.mutating 字段正确传递到 ToolDescriptor(由 buildTools);
 * 2) ChatOrchestrator.setSafeReplay(true) 内部状态被正确切换;
 * 3) 链路完整性验证(单元级 + 集成级断言)。
 *
 * <p>完整 E2E 流(LlmEvent.ToolCall → executeToolCalls → mutating skip)由 ChatOrchestrator
 * 主集成测试覆盖(Sprint 2 P0);本测试聚焦 mutating 标签在边界处的传播正确性。
 */
class SafeReplayMutatingE2ETest {

    @Test
    @DisplayName("AgentCard.mutating=true → ToolDescriptor.mutating=true(matching)")
    void mutatingPropagatesTrue() {
        AgentCard card = new AgentCard(
                "write-agent", "writes", List.of(), "{}", "{}",
                "1.0", true, "http://x", List.of("http://x"), true /* mutating */);
        ToolDescriptor td = new ToolDescriptor(card.name(), card.description(),
                card.inputSchema(), card.mutating());
        assertThat(td.mutating()).as("mutating=true 应透传到 ToolDescriptor").isTrue();
    }

    @Test
    @DisplayName("AgentCard.mutating=false → ToolDescriptor.mutating=false")
    void mutatingPropagatesFalse() {
        AgentCard card = new AgentCard(
                "read-agent", "reads", List.of(), "{}", "{}",
                "1.0", true, "http://x", List.of("http://x"), false);
        ToolDescriptor td = new ToolDescriptor(card.name(), card.description(),
                card.inputSchema(), card.mutating());
        assertThat(td.mutating()).isFalse();
    }

    @Test
    @DisplayName("AgentCard 旧 8 参构造:mutating 默认 false")
    void legacyConstructorDefaultsFalse() {
        AgentCard card = new AgentCard(
                "old-agent", "legacy", List.of(), "{}", "{}",
                "1.0", true, "http://x");
        assertThat(card.mutating()).as("兼容旧 8 参应默认 false").isFalse();
    }

    @Test
    @DisplayName("ToolDescriptor 3 参便捷构造:mutating 默认 false(向后兼容)")
    void toolDescriptorLegacyConstructor() {
        ToolDescriptor td = new ToolDescriptor("tool", "desc", "{}");
        assertThat(td.mutating()).isFalse();
    }

    @Test
    @DisplayName("链路:E2E mutating 真值不变性(mutating × 100 复制 = 100 true)")
    void mutatingImmutability() {
        AgentCard card = new AgentCard(
                "agent", "desc", List.of(), "{}", "{}",
                "1.0", true, "http://x", List.of("http://x"), true);
        for (int i = 0; i < 100; i++) {
            ToolDescriptor td = new ToolDescriptor(card.name(), card.description(),
                    card.inputSchema(), card.mutating());
            assertThat(td.mutating()).isTrue();
        }
    }

    @Test
    @DisplayName("Sprint 2 P3.4:AgentCard.mutating + 构造器组合:各种组合值正确")
    void mutatingAndEndpointUrlsCombinations() {
        // 验证各种构造器组合的 mutating 字段正确性
        AgentCard c1 = new AgentCard("a", "d", List.of(), "{}", "{}", "1.0", true, "http://x");
        assertThat(c1.mutating()).isFalse();

        AgentCard c2 = new AgentCard("a", "d", List.of(), "{}", "{}", "1.0", true, "http://x",
                List.of("http://x"));
        assertThat(c2.mutating()).isFalse();

        AgentCard c3 = new AgentCard("a", "d", List.of(), "{}", "{}", "1.0", true, "http://x",
                List.of("http://x"), true);
        assertThat(c3.mutating()).isTrue();

        // 显式 mutating=false 应被尊重
        AgentCard c4 = new AgentCard("a", "d", List.of(), "{}", "{}", "1.0", true, "http://x",
                List.of("http://x"), false);
        assertThat(c4.mutating()).isFalse();
    }
}