package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * domain service：角色查询 + PolicyPreview 纯函数评估（spec §GW-RBAC-011 + design §3.1）。
 *
 * <p>preview 不读仓储、不写审计、不上 OTel（design §2.3）。调用方需自行传入 roles 快照 + bindings。
 * <p>幂等保证：同入参连发 N 次 equals 一致（spec §验收判定 ⑪）。
 */
public class RoleQueryService {

    public PolicyPreview preview(List<Role> roles, List<RoleId> bindings,
                                 UserId user, TenantId tenant) {
        Set<Role> userRoles = roles.stream()
                .filter(r -> bindings.contains(r.id()))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> allowedAgents = new LinkedHashSet<>();
        Set<ModelId> allowedModels = new LinkedHashSet<>();

        for (Role role : userRoles) {
            for (Permission p : role.permissions()) {
                switch (p) {
                    case AgentPermission ap -> allowedAgents.add(ap.agentName());
                    case ModelPermission mp -> allowedModels.addAll(mp.models());
                    case SkillPermission sp -> {
                        /* D1-4：SkillPermission 一期数据空，preview 跳过 */
                    }
                }
            }
        }

        return new PolicyPreview(user, tenant,
                Set.copyOf(allowedAgents), Set.copyOf(allowedModels));
    }
}
