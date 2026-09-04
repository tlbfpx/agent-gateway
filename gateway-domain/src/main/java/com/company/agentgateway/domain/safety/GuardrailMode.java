package com.company.agentgateway.domain.safety;

/**
 * Guardrail 模式(Round 9):三种互斥行为。
 *
 * <ul>
 *   <li>{@link #OBSERVE} — 仅记录违规,继续执行(适合试运行 / 灰度)</li>
 *   <li>{@link #BLOCK} — 立即拒绝(OBSERVE 时返回错误,不进入下一步)</li>
 *   <li>{@link #REDACT} — 替换敏感内容后继续(仅对 PII / 输出侧有效)</li>
 * </ul>
 */
public enum GuardrailMode {
    OBSERVE,
    BLOCK,
    REDACT
}