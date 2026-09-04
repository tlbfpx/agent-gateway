package com.company.agentgateway.domain.iam;

/**
 * spec §19.2 sealed Permission。D1-4 决策：保留 SkillPermission 子类，
 * 一期数据空，二期 Skill 级 RBAC 零破坏接入。
 *
 * <p>sealed 强制 exhaustiveness（Java 21），Pattern Matching 必须覆盖全部
 * 子类分支（spec §GW-RBAC-003）。
 */
public sealed interface Permission
        permits AgentPermission, ModelPermission, SkillPermission {
}