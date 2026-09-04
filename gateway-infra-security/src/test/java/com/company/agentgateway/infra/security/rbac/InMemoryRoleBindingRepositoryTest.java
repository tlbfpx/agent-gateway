package com.company.agentgateway.infra.security.rbac;

import com.company.agentgateway.domain.iam.RoleBindingRepository;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRoleBindingRepositoryTest {

    @Test
    void crud_basic() {
        RoleBindingRepository repo = new InMemoryRoleBindingRepository();
        TenantId t = new TenantId("t1");
        UserId u = new UserId("u1");
        repo.bind(t, u, new RoleId("r1"));
        repo.bind(t, u, new RoleId("r2"));
        assertThat(repo.findByUser(t, u)).containsExactlyInAnyOrder(
                new RoleId("r1"), new RoleId("r2"));
        repo.unbind(t, u, new RoleId("r1"));
        assertThat(repo.findByUser(t, u)).containsExactly(new RoleId("r2"));
    }

    @Test
    void tenantIsolation() {
        RoleBindingRepository repo = new InMemoryRoleBindingRepository();
        repo.bind(new TenantId("t1"), new UserId("u1"), new RoleId("r1"));
        assertThat(repo.findByUser(new TenantId("t2"), new UserId("u1"))).isEmpty();
    }

    @Test
    void unbind_unbound_returnsQuietly() {
        RoleBindingRepository repo = new InMemoryRoleBindingRepository();
        TenantId t = new TenantId("t1");
        repo.unbind(t, new UserId("u1"), new RoleId("r-不存在")); // 不抛
    }

    @Test
    void bind_duplicate_isIdempotent() {
        RoleBindingRepository repo = new InMemoryRoleBindingRepository();
        TenantId t = new TenantId("t1");
        UserId u = new UserId("u1");
        repo.bind(t, u, new RoleId("r1"));
        repo.bind(t, u, new RoleId("r1")); // 重复绑定幂等（HTTP 层负责 409 校验）
        assertThat(repo.findByUser(t, u)).containsExactly(new RoleId("r1"));
    }
}
