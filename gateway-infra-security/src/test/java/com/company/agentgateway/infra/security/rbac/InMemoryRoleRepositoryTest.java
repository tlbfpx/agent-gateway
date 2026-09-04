package com.company.agentgateway.infra.security.rbac;

import com.company.agentgateway.domain.iam.AgentPermission;
import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRoleRepositoryTest {

    @Test
    void crud_basic() {
        RoleRepository repo = new InMemoryRoleRepository();
        TenantId t = new TenantId("t1");
        Role r = new Role(new RoleId("r1"), "name", "desc",
                Set.of(new AgentPermission("hr-agent", Set.of())));
        repo.save(t, r);
        assertThat(repo.findById(t, new RoleId("r1"))).contains(r);
        assertThat(repo.findAll(t)).hasSize(1);
        repo.delete(t, new RoleId("r1"));
        assertThat(repo.findById(t, new RoleId("r1"))).isEmpty();
    }

    @Test
    void tenantIsolation() {
        RoleRepository repo = new InMemoryRoleRepository();
        repo.save(new TenantId("t1"), new Role(new RoleId("r1"), "n", "d", Set.of()));
        assertThat(repo.findAll(new TenantId("t2"))).isEmpty();
    }

    @Test
    void concurrentSave_50threads_threadSafe() throws InterruptedException {
        RoleRepository repo = new InMemoryRoleRepository();
        TenantId t = new TenantId("t1");
        int n = 50;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch latch = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    repo.save(t, new Role(new RoleId("r-" + idx), "n-" + idx, "d",
                            Set.of(new AgentPermission("a-" + idx, Set.of()))));
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(5, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(repo.findAll(t)).hasSize(n);
    }

    @Test
    void delete_isIdempotent() {
        RoleRepository repo = new InMemoryRoleRepository();
        TenantId t = new TenantId("t1");
        repo.save(t, new Role(new RoleId("r1"), "n", "d", Set.of()));
        repo.delete(t, new RoleId("r1"));
        repo.delete(t, new RoleId("r1")); // 二次删除不抛
        assertThat(repo.findById(t, new RoleId("r1"))).isEmpty();
    }
}
