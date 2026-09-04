package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.*;
import java.util.Set;

/**
 * spec §3.3 + §6.3。含 Agent 级 + 模型级授权判定（domain 层只判定，数据来源由 infra 提供）。
 * 一期扁平 grant 判定；Role/Permission 抽象（spec §19）由后续 RBAC 计划补。
 */
public record AuthPrincipal(UserId user, TenantId tenant,
                            Set<AgentGrant> agentGrants,
                            Set<ModelId> allowedModels,
                            AuthChannel channel) {
    public AuthPrincipal {
        agentGrants = Set.copyOf(agentGrants);
        allowedModels = Set.copyOf(allowedModels);
    }

    public boolean canInvoke(String agentName) {
        return agentGrants.stream().anyMatch(g -> g.agentName().equals(agentName));
    }

    public boolean canUse(ModelId model) {
        return allowedModels.contains(model);
    }

    /** 多租户切换（spec §6.2 二期）：principal 代表的身份可在其授权租户间切换。
     *  target 必须与当前同 user 身份上下文（由 infra 校验 target 在 key/user 的租户列表内）。
     * 本方法只做不可变复制。 */
    public AuthPrincipal switchTenant(TenantId target) {
        return new AuthPrincipal(user, target, agentGrants, allowedModels, channel);
    }
}
