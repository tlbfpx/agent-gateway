package com.company.agentgateway.domain.iam;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/** spec §6.3 AgentGrant。一期 agentName 级；二期 Skill 级细化（allowedSkills 空时 = 全授权）。 */
public record AgentGrant(String agentName, Set<String> allowedSkills) {
    public AgentGrant {
        allowedSkills = Set.copyOf(allowedSkills);
    }

    /** Skill 级 RBAC（spec §6.3 二期）：allowedSkills 为空 = 全授权；非空 = 只保留列出的 skill。 */
    public Set<String> filterSkills(Collection<String> agentSkills) {
        if (allowedSkills.isEmpty()) {
            return Set.copyOf(new java.util.HashSet<>(agentSkills));
        }
        return agentSkills.stream()
                .filter(allowedSkills::contains)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 是否授权该 skill（空 = 全授权）。 */
    public boolean allowsSkill(String skill) {
        return allowedSkills.isEmpty() || allowedSkills.contains(skill);
    }
}
