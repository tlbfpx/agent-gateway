package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Port contract test：验证 RoleRepository 接口签名满足 spec §GW-RBAC-002 契约。
 * 用 InMemory 桩实现（与既有 InMemoryApiKeyStore 风格一致）。
 */
class RoleRepositoryContractTest {

    /** 测试用 InMemory 桩（验证零实现可编译） */
    static class InMemoryStub implements RoleRepository {
        final Map<TenantId, Map<RoleId, Role>> store = new ConcurrentHashMap<>();

        @Override public Optional<Role> findById(TenantId t, RoleId r) {
            return Optional.ofNullable(store.get(t)).map(m -> m.get(r));
        }
        @Override public List<Role> findAll(TenantId t) {
            Map<RoleId, Role> m = store.get(t);
            return m == null ? List.of() : List.copyOf(m.values());
        }
        @Override public void save(TenantId t, Role r) {
            store.computeIfAbsent(t, k -> new ConcurrentHashMap<>()).put(r.id(), r);
        }
        @Override public void delete(TenantId t, RoleId r) {
            Map<RoleId, Role> m = store.get(t);
            if (m != null) m.remove(r);
        }
    }

    @Test
    void findAll_isEmptyOnUnseenTenant() {
        RoleRepository repo = new InMemoryStub();
        assertThat(repo.findAll(new TenantId("t-unseen"))).isEmpty();
    }

    @Test
    void save_thenFindById_returnsSameRole() {
        RoleRepository repo = new InMemoryStub();
        TenantId t = new TenantId("t1");
        Role r = new Role(new RoleId("r1"), "name", "desc",
                Set.of(new AgentPermission("hr-agent", Set.of())));
        repo.save(t, r);
        assertThat(repo.findById(t, new RoleId("r1"))).contains(r);
    }

    @Test
    void delete_removesRole() {
        RoleRepository repo = new InMemoryStub();
        TenantId t = new TenantId("t1");
        repo.save(t, new Role(new RoleId("r1"), "n", "d", Set.of()));
        repo.delete(t, new RoleId("r1"));
        assertThat(repo.findById(t, new RoleId("r1"))).isEmpty();
    }

    @Test
    void tenantIsolation_diffTenantSameRoleId_notVisible() {
        RoleRepository repo = new InMemoryStub();
        repo.save(new TenantId("t1"), new Role(new RoleId("r1"), "n", "d", Set.of()));
        assertThat(repo.findById(new TenantId("t2"), new RoleId("r1"))).isEmpty();
    }

    @Test
    void findAll_returnsAllRolesInTenant() {
        RoleRepository repo = new InMemoryStub();
        TenantId t = new TenantId("t1");
        repo.save(t, new Role(new RoleId("r1"), "n", "d",
                Set.of(new AgentPermission("a", Set.of()))));
        repo.save(t, new Role(new RoleId("r2"), "n", "d",
                Set.of(new ModelPermission(Set.of(new ModelId("qwen"))))));
        assertThat(repo.findAll(t)).hasSize(2);
    }
}
