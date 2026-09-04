package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;

import java.util.List;
import java.util.Optional;

/**
 * 出站端口：角色定义存储（spec §GW-RBAC-002 + §19.4）。
 *
 * <p>所有方法租户隔离：TenantId 为第一参数。
 * <p>实现：InMemoryRoleRepository（本期）/ RoleRepositoryJpa（二期）。
 */
public interface RoleRepository {

    Optional<Role> findById(TenantId tenant, RoleId roleId);

    List<Role> findAll(TenantId tenant);

    void save(TenantId tenant, Role role);

    void delete(TenantId tenant, RoleId roleId);
}
