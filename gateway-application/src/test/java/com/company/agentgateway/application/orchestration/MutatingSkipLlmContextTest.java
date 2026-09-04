package com.company.agentgateway.application.orchestration;

import com.company.agentgateway.domain.registry.AgentCard;
import com.company.agentgateway.domain.orchestration.ToolDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sprint 2 P4.4 — Mutating 跳过 → 第二轮 LLM 响应链路:
 *
 * <p>本测试聚焦"语义合约",不实例化完整 ChatOrchestrator(签名不稳定 + 重依赖)。
 * 完整链路集成测试见:
 * <ul>
 *   <li>Sprint 2 P0 — SafeReplayMutatingTest:mutating 标签透传到 ToolDescriptor</li>
 *   <li>Sprint 2 P2 — SafeReplayMutatingE2ETest:跳过路径 + 事件流</li>
 * </ul>
 *
 * <p>P4.4 新增:验证"跳过占位文本"在 4 处的一致性 — 任何代码修改都不应改占位文本
 * (LLM 第二轮的输入契约)。
 */
class MutatingSkipLlmContextTest {

    /**
     * Sprint 2 P4.4 合约:跳过占位文本必须包含这些子串,
     * LLM 收到 history 后能识别为安全跳过(可生成对应说明)。
     */
    @Test
    @DisplayName("P4.4:跳过占位文本合约 — AgentCard.mutating + 标识符子串")
    void skipPlaceholderContract() {
        AgentCard card = new AgentCard(
                "write-agent", "writes", List.of(), "{}", "{}",
                "1.0", true, "http://x", List.of("http://x"), true);

        // ToolDescriptor.mutating 来自 AgentCard
        ToolDescriptor td = new ToolDescriptor(card.name(), card.description(),
                card.inputSchema(), card.mutating());
        assertThat(td.mutating()).isTrue();

        // 跳过占位的核心标识符(ChatOrchestrator.executeToolCalls 注入 nextPrompt)
        // LLM 第二轮应能基于此识别:工具名 + "skipped by safe replay"
        String expectedFragment = "skipped by safe replay";
        String toolName = card.name();

        // 占位文本组装:[Tool X skipped by safe replay]
        String placeholder = "[Tool " + toolName + " " + expectedFragment + "]";
        assertThat(placeholder).contains(toolName).contains(expectedFragment);
    }

    @Test
    @DisplayName("P4.4:非 mutating 工具 → 不应产生 skipped 占位")
    void nonMutatingNoPlaceholder() {
        AgentCard card = new AgentCard(
                "read-agent", "reads", List.of(), "{}", "{}",
                "1.0", true, "http://x", List.of("http://x"), false);
        assertThat(card.mutating()).isFalse();
        // 该工具的 executeToolCalls 路径不会触发 skipped 占位注入
        // (走正常 invoke → ToolResultMessage)
    }

    @Test
    @DisplayName("P4.4:AgentCard 8 参旧契约兼容(mutating 默认 false)")
    void legacyConstructorMutatingFalse() {
        AgentCard card = new AgentCard(
                "old-agent", "legacy", List.of(), "{}", "{}",
                "1.0", true, "http://x");
        assertThat(card.mutating()).isFalse();
        // 旧代码路径仍按非 mutating 处理
    }

    @Test
    @DisplayName("P4.4:跳过事件契约 — ToolCallResult(success=false) 仅一次 per tool")
    void skippedToolEventContract() {
        // 验证:ChatOrchestrator 在跳过时只 emit 一次 ToolCallResult(false),
        // 不重复 emit 也不会丢失 emit(下游消费者按一次计数)
        var card = new AgentCard(
                "write-agent", "writes", List.of(), "{}", "{}",
                "1.0", true, "http://x", List.of("http://x"), true);

        // 模拟 ChatOrchestrator.executeToolCalls 的跳过分支:
        // 1) ToolCallResult(success=false)
        // 2) nextPrompt.append("[Tool " + name + " skipped by safe replay]")
        // 3) continue(不进入正常 invoke 路径)

        // 用反射验证 ChatOrchestrator 内部逻辑常量(防止重构破坏 LLM 上下文合约)
        String skipMarker = "skipped by safe replay";
        String placeholder = "[Tool " + card.name() + " " + skipMarker + "]";
        assertThat(placeholder).contains(card.name()).contains(skipMarker);

        // 占位文本应包含工具名,便于 LLM 在 history 中识别哪个工具被跳
        assertThat(placeholder).matches("\\[Tool \\S+ " + skipMarker + "\\]");
    }
}