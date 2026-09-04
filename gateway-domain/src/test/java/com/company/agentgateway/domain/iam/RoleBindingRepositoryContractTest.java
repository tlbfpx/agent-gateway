package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class RoleBindingRepositoryContractTest {

    /** 测试用 InMemory 桩 */
    static class InMemoryStub implements RoleBindingRepository {
        // tenant -> (user -> Set<RoleId>)
        final Map<TenantId, Map<UserId, Set<RoleId>>> store = new ConcurrentHashMap<>();

        @Override public List<RoleId> findByUser(TenantId t, UserId u) {
            Map<UserId, Set<RoleId>> m = store.get(t);
            if (m == null) return List.of();
            Set<RoleId> s = m.get(u);
            return s == null ? List.of() : List.copyOf(s);
        }
        @Override public void bind(TenantId t, UserId u, RoleId r) {
            store.computeIfAbsent(t, k -> new ConcurrentHashMap<>())
                 .computeIfAbsent(u, k -> ConcurrentHashMap.newKeySet())
                 .add(r);
        }
        @Override public void unbind(TenantId t, UserId u, RoleId r) {
            Map<UserId, Set<RoleId>> m = store.get(t);
            if (m != null) {
                Set<RoleId> s = m.get(u);
                if (s != null) s.remove(r);
            }
        }
    }

    @Test
    void findByUser_returnsEmpty_whenNeverBound() {
        RoleBindingRepository repo = new InMemoryStub();
        assertThat(repo.findByUser(new TenantId("t1"), new UserId("u1"))).isEmpty();
    }

    @Test
    void bind_thenFindByUser_returnsRoleId() {
        RoleBindingRepository repo = new InMemoryStub();
        repo.bind(new TenantId("t1"), new UserId("u1"), new RoleId("r1"));
        assertThat(repo.findByUser(new TenantId("t1"), new UserId("u1")))
                .containsExactly(new RoleId("r1"));
    }

    @Test
    void unbind_removesBinding() {
        RoleBindingRepository repo = new InMemoryStub();
        TenantId t = new TenantId("t1");
        repo.bind(t, new UserId("u1"), new RoleId("r1"));
        repo.unbind(t, new UserId("u1"), new RoleId("r1"));
        assertThat(repo.findByUser(t, new UserId("u1"))).isEmpty();
    }

    @Test
    void tenantIsolation_diffTenant_notVisible() {
        RoleBindingRepository repo = new InMemoryStub();
        repo.bind(new TenantId("t1"), new UserId("u1"), new RoleId("r1"));
        assertThat(repo.findByUser(new TenantId("t2"), new UserId("u1"))).isEmpty();
    }

    @Test
    void bind_multipleRolesToSameUser_returnsAll() {
        RoleBindingRepository repo = new InMemoryStub();
        TenantId t = new TenantId("t1");
        UserId u = new UserId("u1");
        repo.bind(t, u, new RoleId("r1"));
        repo.bind(t, u, new RoleId("r2"));
        repo.bind(t, u, new RoleId("r3"));
        assertThat(repo.findByUser(t, u)).hasSize(3);
    }
}
