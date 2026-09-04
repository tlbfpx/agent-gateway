package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;

/**
 * 仓储内部值对象（design §4.2）：租户×用户×角色三元组。
 * 表 rbac_role_binding 的主键。
 */
public record RoleBinding(TenantId tenant, UserId user, RoleId roleId) {
    public RoleBinding {
        if (tenant == null) throw new IllegalArgumentException("tenant must not be null");
        if (user == null) throw new IllegalArgumentException("user must not be null");
        if (roleId == null) throw new IllegalArgumentException("roleId must not be null");
    }
}
