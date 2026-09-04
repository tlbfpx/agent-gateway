package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import java.util.Optional;

/**
 * Permission 评估器（spec §GW-RBAC-003 Pattern Matching exhaustiveness）。
 *
 * <p>Java 21 sealed 强制 exhaustiveness；如漏分支编译失败。
 * <p>evaluateSkill 一期返回 {@code Optional.empty()}（D1-4 决策）— 调用方按 ALLOWED-with-skip 处理。
 */
public final class PolicyEvaluator {

    private PolicyEvaluator() {}

    public static Optional<Boolean> evaluateAgent(AgentPermission ap, String agentName) {
        return Optional.of(ap.agentName().equals(agentName));
    }

    public static Optional<Boolean> evaluateModel(ModelPermission mp, ModelId model) {
        return Optional.of(mp.models().contains(model));
    }

    /**
     * Skill 级 RBAC（D1-4 一期数据空）：返回 Optional.empty() 让评估链按
     * "ALLOWED-with-skip" 跳过 Skill 维度（spec §GW-RBAC-003 注释）。
     * 二期填数据时改为 evaluateSkill(sp) -> ap.agentName().equals(...) && ...
     */
    public static Optional<Boolean> evaluateSkill(SkillPermission sp) {
        return Optional.empty();
    }

    /** 通用模式匹配入口（spec §GW-RBAC-003） */
    public static Optional<Boolean> evaluatePermission(Permission p, String agentName, ModelId model) {
        return switch (p) {
            case AgentPermission ap -> evaluateAgent(ap, agentName);
            case ModelPermission mp -> evaluateModel(mp, model);
            case SkillPermission sp -> evaluateSkill(sp);
        };
    }
}
