package com.company.agentgateway.domain.safety;

import java.time.Instant;

/**
 * Guardrail 违规事件(Round 9):
 * 检测到敏感内容时记录,用于 audit log + metrics 上报。
 *
 * @param rule     触发的规则类型(PIE / JAILBREAK / TOXICITY / TOOL_NOT_IN_ALLOWLIST / TOOL_IN_BLOCKLIST)
 * @param severity 严重程度(LOW / MEDIUM / HIGH)
 * @param matchedText 命中的内容(已脱敏或截断,避免日志泄漏)
 * @param action   采取的动作(OBSERVED / BLOCKED / REDACTED)
 * @param tenant   租户
 * @param traceId  关联的 traceId(可能为 null)
 * @param occurredAt 事件时间
 */
public record GuardrailViolation(
        Rule rule,
        Severity severity,
        String matchedText,
        Action action,
        String tenant,
        String traceId,
        Instant occurredAt
) {
    public enum Rule { PII, JAILBREAK, TOXICITY, TOOL_NOT_IN_ALLOWLIST, TOOL_IN_BLOCKLIST }
    public enum Severity { LOW, MEDIUM, HIGH }
    public enum Action { OBSERVED, BLOCKED, REDACTED }

    public GuardrailViolation {
        if (rule == null) throw new IllegalArgumentException("rule required");
        if (severity == null) severity = Severity.MEDIUM;
        if (action == null) action = Action.OBSERVED;
        if (occurredAt == null) occurredAt = Instant.now();
    }

    /** 截断 matchedText 到 64 字符,避免日志/指标膨胀。 */
    public GuardrailViolation withTruncatedMatch() {
        if (matchedText == null || matchedText.length() <= 64) return this;
        return new GuardrailViolation(rule, severity, matchedText.substring(0, 64) + "...", action, tenant, traceId, occurredAt);
    }
}