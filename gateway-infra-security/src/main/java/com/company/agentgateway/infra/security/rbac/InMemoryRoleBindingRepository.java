package com.company.agentgateway.infra.security.rbac;

import com.company.agentgateway.domain.iam.RoleBindingRepository;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemory RoleBindingRepository（spec §GW-RBAC-004 · design §2.1）。
 *
 * <p>结构：ConcurrentHashMap&lt;TenantId, ConcurrentHashMap&lt;UserId, Set&lt;RoleId&gt;&gt;&gt;。
 */
@Component
@ConditionalOnMissingBean(RoleBindingRepository.class)
public class InMemoryRoleBindingRepository implements RoleBindingRepository {

    private final Map<TenantId, Map<UserId, Set<RoleId>>> store = new ConcurrentHashMap<>();

    @Override
    public List<RoleId> findByUser(TenantId tenant, UserId user) {
        Map<UserId, Set<RoleId>> inner = store.get(tenant);
        if (inner == null) return List.of();
        Set<RoleId> s = inner.get(user);
        return s == null ? List.of() : List.copyOf(s);
    }

    @Override
    public void bind(TenantId tenant, UserId user, RoleId roleId) {
        store.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
             .computeIfAbsent(user, k -> ConcurrentHashMap.newKeySet())
             .add(roleId);
    }

    @Override
    public void unbind(TenantId tenant, UserId user, RoleId roleId) {
        Map<UserId, Set<RoleId>> inner = store.get(tenant);
        if (inner == null) return;
        Set<RoleId> s = inner.get(user);
        if (s != null) s.remove(roleId);
    }
}
