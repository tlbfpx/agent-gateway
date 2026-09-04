package com.company.agentgateway.domain.safety;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Guardrail 门面(Round 9):编排三个 check + 报告违规事件。
 *
 * <p>持有当前 {@link GuardrailPolicy}(可通过 {@link #updatePolicy} 热更新),
 * 委托 {@link GuardrailPort} 做实际检测;违规通过 {@link Consumer} 上报(供 audit + metrics)。
 *
 * <p><b>域零框架(GW-GRD-014)</b>:仅依赖 java.* + domain 接口,无 SLF4J / Spring。
 */
public final class GuardrailFacade {

    private final GuardrailPort port;
    private final AtomicReference<GuardrailPolicy> policy;
    private final Consumer<GuardrailViolation> violationSink;

    public GuardrailFacade(GuardrailPort port, GuardrailPolicy initialPolicy,
                            Consumer<GuardrailViolation> violationSink) {
        this.port = Objects.requireNonNull(port, "port");
        this.policy = new AtomicReference<>(initialPolicy != null ? initialPolicy : GuardrailPolicy.disabled());
        this.violationSink = Objects.requireNonNull(violationSink, "violationSink");
    }

    /**
      输入检查 + 按 mode 决定 action。
      @return GuardrailDecision { allowOrRedact, violations, redactedText(REDACT 模式) }
     */
    public InputDecision checkInput(String query, String tenant, String traceId) {
        GuardrailPolicy p = policy.get();
        if (p.mode() == GuardrailMode.OBSERVE && p.toxicityKeywords().isEmpty()
                && p.piiPatterns().isEmpty() && p.jailbreakPatterns().isEmpty()) {
            return new InputDecision(true, query, List.of());
        }
        List<GuardrailViolation> violations = port.checkInput(query, tenant, traceId);
        violations.forEach(v -> violationSink.accept(v.withTruncatedMatch()));
        if (violations.isEmpty()) return new InputDecision(true, query, List.of());

        return switch (p.mode()) {
            case BLOCK -> new InputDecision(false, query, violations);
            case OBSERVE -> new InputDecision(true, query, violations);
            case REDACT -> {
                PiiRedactor.RedactionResult r = PiiRedactor.redact(query, p.piiPatterns());
                yield new InputDecision(true, r.redacted(), violations);
            }
        };
    }

    /**
      输出检查 + 按 mode 决定 action。
     */
    public OutputDecision checkOutput(String response, String tenant, String traceId) {
        GuardrailPolicy p = policy.get();
        List<GuardrailViolation> violations = port.checkOutput(response, tenant, traceId);
        violations.forEach(v -> violationSink.accept(v.withTruncatedMatch()));
        if (violations.isEmpty()) return new OutputDecision(true, response, List.of());

        return switch (p.mode()) {
            case BLOCK -> new OutputDecision(false, response, violations);
            case OBSERVE -> new OutputDecision(true, response, violations);
            case REDACT -> {
                PiiRedactor.RedactionResult r = PiiRedactor.redact(response, p.piiPatterns());
                yield new OutputDecision(true, r.redacted(), violations);
            }
        };
    }

    /**
      工具调用检查 + 按 mode 决定 action。
     */
    public ToolDecision checkToolCall(String toolName, String argsJson, String tenant, String traceId) {
        GuardrailPolicy p = policy.get();
        List<GuardrailViolation> violations = port.checkToolCall(toolName, argsJson, tenant, traceId);
        violations.forEach(v -> violationSink.accept(v.withTruncatedMatch()));
        if (violations.isEmpty()) return new ToolDecision(true, violations);

        boolean block = p.mode() == GuardrailMode.BLOCK && !violations.isEmpty();
        return new ToolDecision(!block, violations);
    }

    /** 热更新策略(运营端点触发)。 */
    public void updatePolicy(GuardrailPolicy newPolicy) {
        policy.set(Objects.requireNonNull(newPolicy, "policy"));
    }

    public GuardrailPolicy currentPolicy() {
        return policy.get();
    }

    public record InputDecision(boolean allowed, String effectiveText, List<GuardrailViolation> violations) {}
    public record OutputDecision(boolean allowed, String effectiveText, List<GuardrailViolation> violations) {}
    public record ToolDecision(boolean allowed, List<GuardrailViolation> violations) {}
}