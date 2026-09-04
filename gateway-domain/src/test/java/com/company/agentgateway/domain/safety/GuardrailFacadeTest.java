package com.company.agentgateway.domain.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GuardrailFacade 单元测试(Round 9):
 * 验证 mode 切换 / 违规收集 / 热更新策略。
 */
class GuardrailFacadeTest {

    @Test
    @DisplayName("GW-GRD-001:disabled 策略 → 全部放行,无违规")
    void disabledBypassesChecks() {
        List<GuardrailViolation> sink = new ArrayList<>();
        var port = stubPort(List.of(), List.of(), List.of());
        GuardrailFacade f = new GuardrailFacade(port, GuardrailPolicy.disabled(), sink::add);

        var in = f.checkInput("any", "t", "tr");
        var out = f.checkOutput("any", "t", "tr");
        var tool = f.checkToolCall("any", "{}", "t", "tr");

        assertThat(in.allowed()).isTrue();
        assertThat(out.allowed()).isTrue();
        assertThat(tool.allowed()).isTrue();
        assertThat(sink).isEmpty();
    }

    @Test
    @DisplayName("GW-GRD-007:BLOCK 模式 → 输入命中违规 → 不允许")
    void blockModeRejectsInput() {
        var violations = List.of(new GuardrailViolation(
                GuardrailViolation.Rule.PII, GuardrailViolation.Severity.HIGH,
                "user@example.com", GuardrailViolation.Action.BLOCKED, "t", "tr", null));
        var port = stubPort(violations, List.of(), List.of());
        GuardrailFacade f = new GuardrailFacade(port, GuardrailPolicy.defaultSafe(),
                v -> {});

        var in = f.checkInput("email me at user@example.com", "t", "tr");
        assertThat(in.allowed()).isFalse();
        assertThat(in.violations()).hasSize(1);
        assertThat(in.violations().get(0).rule()).isEqualTo(GuardrailViolation.Rule.PII);
    }

    @Test
    @DisplayName("GW-GRD-007:OBSERVE 模式 → 违规仅记录,继续放行")
    void observeModeLogsButAllows() {
        var violations = List.of(new GuardrailViolation(
                GuardrailViolation.Rule.TOXICITY, GuardrailViolation.Severity.LOW,
                "idiot", GuardrailViolation.Action.OBSERVED, "t", "tr", null));
        var port = stubPort(violations, List.of(), List.of());
        var captured = new ArrayList<GuardrailViolation>();
        // 注:policy 必须非空,否则 facade 在 OBSERVE 模式下 early return(GW-GRD-001 短路)
        GuardrailFacade f = new GuardrailFacade(port,
                new GuardrailPolicy(GuardrailMode.OBSERVE,
                        List.of("idiot"),  // toxicity keyword
                        List.of(), List.of(), Set.of(), Set.of()),
                captured::add);

        var in = f.checkInput("you are idiot", "t", "tr");
        assertThat(in.allowed()).isTrue();
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).rule()).isEqualTo(GuardrailViolation.Rule.TOXICITY);
    }

    @Test
    @DisplayName("GW-GRD-007:REDACT 模式 + PII 命中 → 文本替换为 [REDACTED:PI]")
    void redactModeRedactsOutput() {
        var violations = List.of(new GuardrailViolation(
                GuardrailViolation.Rule.PII, GuardrailViolation.Severity.HIGH,
                "user@example.com", GuardrailViolation.Action.REDACTED, "t", "tr", null));
        var port = stubPort(List.of(), violations, List.of());
        GuardrailFacade f = new GuardrailFacade(port,
                new GuardrailPolicy(GuardrailMode.REDACT, List.of(),
                        DefaultGuardrailLibrary.DEFAULT_PII_PATTERNS, List.of(), Set.of(), Set.of()),
                v -> {});

        var out = f.checkOutput("contact me at user@example.com", "t", "tr");
        assertThat(out.allowed()).isTrue();
        assertThat(out.effectiveText()).contains("[REDACTED:PI]");
        assertThat(out.effectiveText()).doesNotContain("user@example.com");
    }

    @Test
    @DisplayName("GW-GRD-005:tool 不在白名单 → BLOCK")
    void toolNotInAllowListBlocks() {
        var port = stubPortTool(List.of(new GuardrailViolation(
                GuardrailViolation.Rule.TOOL_NOT_IN_ALLOWLIST, GuardrailViolation.Severity.HIGH,
                "dangerous_tool", GuardrailViolation.Action.BLOCKED, "t", "tr", null)));
        GuardrailFacade f = new GuardrailFacade(port,
                new GuardrailPolicy(GuardrailMode.BLOCK, List.of(), List.of(), List.of(),
                        Set.of("safe_tool_a", "safe_tool_b"), Set.of()),
                v -> {});

        var tool = f.checkToolCall("dangerous_tool", "{}", "t", "tr");
        assertThat(tool.allowed()).isFalse();
        assertThat(tool.violations().get(0).rule())
                .isEqualTo(GuardrailViolation.Rule.TOOL_NOT_IN_ALLOWLIST);
    }

    @Test
    @DisplayName("GW-GRD-006:tool 在黑名单 → BLOCK")
    void toolInBlockListBlocks() {
        var port = stubPortTool(List.of(new GuardrailViolation(
                GuardrailViolation.Rule.TOOL_IN_BLOCKLIST, GuardrailViolation.Severity.HIGH,
                "forbidden_tool", GuardrailViolation.Action.BLOCKED, "t", "tr", null)));
        GuardrailFacade f = new GuardrailFacade(port,
                new GuardrailPolicy(GuardrailMode.BLOCK, List.of(), List.of(), List.of(),
                        Set.of(), Set.of("forbidden_tool")),
                v -> {});

        var tool = f.checkToolCall("forbidden_tool", "{}", "t", "tr");
        assertThat(tool.allowed()).isFalse();
    }

    @Test
    @DisplayName("GW-GRD-011:策略热更新立即生效")
    void policyUpdateTakesEffectImmediately() {
        var port = stubPortTool(List.of());
        GuardrailFacade f = new GuardrailFacade(port, GuardrailPolicy.disabled(), v -> {});

        // disabled → allowed
        assertThat(f.checkToolCall("any", "{}", "t", "tr").allowed()).isTrue();

        // update to strict
        var strictPort = stubPortTool(List.of(new GuardrailViolation(
                GuardrailViolation.Rule.TOOL_IN_BLOCKLIST, GuardrailViolation.Severity.HIGH,
                "x", GuardrailViolation.Action.BLOCKED, "t", "tr", null)));
        GuardrailFacade g = new GuardrailFacade(strictPort, GuardrailPolicy.disabled(), v -> {});
        g.updatePolicy(new GuardrailPolicy(GuardrailMode.BLOCK, List.of(), List.of(), List.of(),
                Set.of(), Set.of("x")));
        assertThat(g.currentPolicy().toolBlockList()).contains("x");
    }

    // ===== helper: stub port that returns pre-canned violations =====

    private static GuardrailPort stubPort(List<GuardrailViolation> input,
                                          List<GuardrailViolation> output,
                                          List<GuardrailViolation> tool) {
        return new GuardrailPort() {
            @Override public List<GuardrailViolation> checkInput(String q, String t, String tr) { return input; }
            @Override public List<GuardrailViolation> checkOutput(String r, String t, String tr) { return output; }
            @Override public List<GuardrailViolation> checkToolCall(String n, String a, String t, String tr) { return tool; }
        };
    }

    private static GuardrailPort stubPortTool(List<GuardrailViolation> tool) {
        return stubPort(List.of(), List.of(), tool);
    }
}