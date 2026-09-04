package com.company.agentgateway.domain.safety;

import java.util.List;
import java.util.Set;

/**
 * Guardrail 策略(Round 9):
 * domain 纯 record,零框架依赖。
 *
 * <h2>字段</h2>
 * <ul>
 *   <li>{@link GuardrailMode} — 全局模式(OBSERVE 仅日志 / BLOCK 拒绝 / REDACT 脱敏)</li>
 *   <li>toxicityKeywords — toxicity 关键词列表(中英文)</li>
 *   <li>piiPatterns — PII 正则模式列表(邮箱/手机/身份证/银行卡)</li>
 *   <li>jailbreakPatterns — jailbreak 模式列表(中英文)</li>
 *   <li>toolAllowList — 工具白名单(空 = 不限制)</li>
 *   <li>toolBlockList — 工具黑名单(优先级高于白名单)</li>
 * </ul>
 */
public record GuardrailPolicy(
        GuardrailMode mode,
        List<String> toxicityKeywords,
        List<String> piiPatterns,
        List<String> jailbreakPatterns,
        Set<String> toolAllowList,
        Set<String> toolBlockList
) {
    public GuardrailPolicy {
        if (mode == null) mode = GuardrailMode.OBSERVE;
        if (toxicityKeywords == null) toxicityKeywords = List.of();
        if (piiPatterns == null) piiPatterns = List.of();
        if (jailbreakPatterns == null) jailbreakPatterns = List.of();
        if (toolAllowList == null) toolAllowList = Set.of();
        if (toolBlockList == null) toolBlockList = Set.of();
    }

    /** 默认策略:仅 OBSERVE 模式,空规则集(放行所有)。 */
    public static GuardrailPolicy disabled() {
        return new GuardrailPolicy(GuardrailMode.OBSERVE, List.of(), List.of(), List.of(), Set.of(), Set.of());
    }

    /** 默认安全策略:BLOCK 模式 + 内置 PII / jailbreak / toxicity 规则。 */
    public static GuardrailPolicy defaultSafe() {
        return new GuardrailPolicy(
                GuardrailMode.BLOCK,
                DefaultGuardrailLibrary.DEFAULT_TOXICITY_KEYWORDS,
                DefaultGuardrailLibrary.DEFAULT_PII_PATTERNS,
                DefaultGuardrailLibrary.DEFAULT_JAILBREAK_PATTERNS,
                Set.of(),
                Set.of()
        );
    }
}