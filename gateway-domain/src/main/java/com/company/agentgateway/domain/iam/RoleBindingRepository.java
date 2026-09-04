package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;

import java.util.List;

/**
 * 出站端口：用户→角色 绑定存储（spec §GW-RBAC-002 + §19.4）。
 *
 * <p>租户隔离；实现：InMemoryRoleBindingRepository（本期）/ RoleBindingRepositoryJpa（二期）。
 */
public interface RoleBindingRepository {

    List<RoleId> findByUser(TenantId tenant, UserId user);

    void bind(TenantId tenant, UserId user, RoleId roleId);

    void unbind(TenantId tenant, UserId user, RoleId roleId);
}
