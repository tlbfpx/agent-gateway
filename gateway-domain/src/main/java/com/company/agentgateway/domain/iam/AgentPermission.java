package com.company.agentgateway.domain.iam;

import java.util.Set;

/**
 * spec §19.2 AgentPermission。allowedSkills 为空 = 全授权（D1 一期允许空，
 * Skill 级细化沿用既有 AgentGrant.allowsSkill 语义）。
 */
public record AgentPermission(String agentName, Set<String> allowedSkills) implements Permission {
    public AgentPermission {
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("agentName must not be blank");
        }
        allowedSkills = Set.copyOf(allowedSkills);
    }
}
