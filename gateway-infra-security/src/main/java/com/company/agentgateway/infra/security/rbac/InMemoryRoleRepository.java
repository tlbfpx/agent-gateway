package com.company.agentgateway.infra.security.rbac;

import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemory RoleRepository（spec §GW-RBAC-004 · design §2.1）。
 *
 * <p>结构：ConcurrentHashMap&lt;TenantId, ConcurrentHashMap&lt;RoleId, Role&gt;&gt;。
 * 二期 JPA 实现通过 @ConditionalOnMissingBean 覆盖。
 */
@Component
@ConditionalOnMissingBean(RoleRepository.class)
public class InMemoryRoleRepository implements RoleRepository {

    private final Map<TenantId, Map<RoleId, Role>> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Role> findById(TenantId tenant, RoleId roleId) {
        Map<RoleId, Role> inner = store.get(tenant);
        return Optional.ofNullable(inner).map(m -> m.get(roleId));
    }

    @Override
    public List<Role> findAll(TenantId tenant) {
        Map<RoleId, Role> inner = store.get(tenant);
        return inner == null ? List.of() : List.copyOf(inner.values());
    }

    @Override
    public void save(TenantId tenant, Role role) {
        store.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>()).put(role.id(), role);
    }

    @Override
    public void delete(TenantId tenant, RoleId roleId) {
        Map<RoleId, Role> inner = store.get(tenant);
        if (inner != null) inner.remove(roleId);
    }
}
