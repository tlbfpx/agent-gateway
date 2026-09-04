package com.company.agentgateway.domain.iam;

/**
 * spec §19.2 SkillPermission。**一期数据空**（D1-4 决策：保留 sealed 子类，
 * 不接管理 REST，不入 Nacos 热更新路径）。
 *
 * <p>二期接入 Skill 级 RBAC 时复用此类型，零破坏。
 */
public record SkillPermission(String agentName, String skillName) implements Permission {
    public SkillPermission {
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("agentName must not be blank");
        }
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalArgumentException("skillName must not be blank");
        }
    }
}
