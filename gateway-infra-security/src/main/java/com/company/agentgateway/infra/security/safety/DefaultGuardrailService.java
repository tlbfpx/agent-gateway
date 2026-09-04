package com.company.agentgateway.infra.security.safety;

import com.company.agentgateway.domain.safety.DefaultGuardrailLibrary;
import com.company.agentgateway.domain.safety.GuardrailPolicy;
import com.company.agentgateway.domain.safety.GuardrailPort;
import com.company.agentgateway.domain.safety.GuardrailViolation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 默认 GuardrailPort 实现(Round 9):
 * 三个检查点基于 GuardrailPolicy 中的 regex / keyword 集合。
 *
 * <p>所有匹配是大小写敏感(中文)或大小写不敏感(英文,by (?i) flag);
 * 命中即产生一条 GuardrailViolation,不做自动决策(由 GuardrailFacade 按 policy.mode 处理)。
 *
 * <p>infra 层依赖 SLF4J + java.util.regex;无 Spring / DB 依赖。
 */
public class DefaultGuardrailService implements GuardrailPort {

    private volatile GuardrailPolicy policy;

    public DefaultGuardrailService(GuardrailPolicy initialPolicy) {
        this.policy = initialPolicy != null ? initialPolicy : GuardrailPolicy.disabled();
    }

    /** 热更新策略(GuardrailAdminController 触发)。 */
    public void updatePolicy(GuardrailPolicy newPolicy) {
        this.policy = newPolicy;
    }

    public GuardrailPolicy currentPolicy() {
        return policy;
    }

    @Override
    public List<GuardrailViolation> checkInput(String query, String tenant, String traceId) {
        if (query == null || query.isEmpty()) return List.of();
        List<GuardrailViolation> result = new ArrayList<>();
        // PII
        for (String p : policy.piiPatterns()) {
            Pattern compiled = Pattern.compile(p);
            var m = compiled.matcher(query);
            if (m.find()) {
                result.add(new GuardrailViolation(
                        GuardrailViolation.Rule.PII,
                        GuardrailViolation.Severity.HIGH,
                        m.group(),
                        actionFor(GuardrailViolation.Rule.PII),
                        tenant, traceId, Instant.now()));
            }
        }
        // Jailbreak
        for (String p : policy.jailbreakPatterns()) {
            Pattern compiled = Pattern.compile(p);
            var m = compiled.matcher(query);
            if (m.find()) {
                result.add(new GuardrailViolation(
                        GuardrailViolation.Rule.JAILBREAK,
                        GuardrailViolation.Severity.HIGH,
                        m.group(),
                        actionFor(GuardrailViolation.Rule.JAILBREAK),
                        tenant, traceId, Instant.now()));
            }
        }
        // Toxicity(关键词匹配 — 中英文按 ?i 不分大小写)
        for (String kw : policy.toxicityKeywords()) {
            if (query.contains(kw) || query.toLowerCase().contains(kw.toLowerCase())) {
                result.add(new GuardrailViolation(
                        GuardrailViolation.Rule.TOXICITY,
                        GuardrailViolation.Severity.MEDIUM,
                        kw,
                        actionFor(GuardrailViolation.Rule.TOXICITY),
                        tenant, traceId, Instant.now()));
            }
        }
        return result;
    }

    @Override
    public List<GuardrailViolation> checkOutput(String response, String tenant, String traceId) {
        // 输出侧只检查 PII(其他规则一般不适用)
        if (response == null || response.isEmpty()) return List.of();
        List<GuardrailViolation> result = new ArrayList<>();
        for (String p : policy.piiPatterns()) {
            Pattern compiled = Pattern.compile(p);
            var m = compiled.matcher(response);
            if (m.find()) {
                result.add(new GuardrailViolation(
                        GuardrailViolation.Rule.PII,
                        GuardrailViolation.Severity.HIGH,
                        m.group(),
                        actionFor(GuardrailViolation.Rule.PII),
                        tenant, traceId, Instant.now()));
            }
        }
        return result;
    }

    @Override
    public List<GuardrailViolation> checkToolCall(String toolName, String argsJson, String tenant, String traceId) {
        if (toolName == null) return List.of();
        List<GuardrailViolation> result = new ArrayList<>();
        // 黑名单优先
        if (policy.toolBlockList().contains(toolName)) {
            result.add(new GuardrailViolation(
                    GuardrailViolation.Rule.TOOL_IN_BLOCKLIST,
                    GuardrailViolation.Severity.HIGH,
                    toolName,
                    actionFor(GuardrailViolation.Rule.TOOL_IN_BLOCKLIST),
                    tenant, traceId, Instant.now()));
            return result;
        }
        // 白名单:非空时不在列表 → BLOCK
        if (!policy.toolAllowList().isEmpty() && !policy.toolAllowList().contains(toolName)) {
            result.add(new GuardrailViolation(
                    GuardrailViolation.Rule.TOOL_NOT_IN_ALLOWLIST,
                    GuardrailViolation.Severity.HIGH,
                    toolName,
                    actionFor(GuardrailViolation.Rule.TOOL_NOT_IN_ALLOWLIST),
                    tenant, traceId, Instant.now()));
        }
        return result;
    }

    private GuardrailViolation.Action actionFor(GuardrailViolation.Rule rule) {
        // 默认 action 由 mode 决定,这里仅打 OBSERVED(实际处理由 facade)
        return switch (policy.mode()) {
            case BLOCK -> GuardrailViolation.Action.BLOCKED;
            case REDACT -> rule == GuardrailViolation.Rule.PII ? GuardrailViolation.Action.REDACTED : GuardrailViolation.Action.OBSERVED;
            case OBSERVE -> GuardrailViolation.Action.OBSERVED;
        };
    }
}