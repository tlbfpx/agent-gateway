package com.company.agentgateway.infra.persistence;

import com.company.agentgateway.domain.session.Session;
import com.company.agentgateway.domain.session.UserMessage;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.SessionId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySessionRepositoryTest {

    private static final TenantId T = new TenantId("t1");
    private static final UserId U = new UserId("u1");
    private static final ModelId M = new ModelId("qwen");

    private final InMemorySessionRepository repo = new InMemorySessionRepository();

    @Test
    void load不存在返回null() {
        assertThat(repo.load(new SessionId("ghost"))).isNull();
    }

    @Test
    void create生成新会话_空历史_唯一id() {
        Session a = repo.create(T, U, M);
        Session b = repo.create(T, U, M);

        assertThat(a.tenant()).isEqualTo(T);
        assertThat(a.user()).isEqualTo(U);
        assertThat(a.model()).isEqualTo(M);
        assertThat(a.history()).isEmpty();
        assertThat(a.id()).isNotEqualTo(b.id()); // 唯一 id
        assertThat(repo.load(a.id())).isEqualTo(a);
    }

    @Test
    void save更新历史_load反映() {
        Session s = repo.create(T, U, M);
        Session updated = s.append(new UserMessage("hello"));
        repo.save(updated);

        Session loaded = repo.load(s.id());
        assertThat(loaded.history()).hasSize(1);
        assertThat(loaded.lastActiveAt()).isAfterOrEqualTo(s.createdAt());
    }

    @Test
    void findByUser按lastActiveAt倒序_分页() throws Exception {
        Session s1 = repo.create(T, U, M);
        // 让 s2 的 lastActiveAt 更晚（save 时 Instant.now()）
        Thread.sleep(5);
        Session s2 = repo.create(T, U, M);
        repo.save(s2.append(new UserMessage("x")));

        var page1 = repo.findByUser(T, U, 0, 1);
        assertThat(page1).hasSize(1);
        assertThat(page1.get(0).id()).isEqualTo(s2.id()); // 最新的在前

        var all = repo.findByUser(T, U, 0, 10);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).lastActiveAt()).isAfterOrEqualTo(all.get(1).lastActiveAt());

        // 分页 offset
        assertThat(repo.findByUser(T, U, 1, 10)).hasSize(1);
    }

    @Test
    void findByUser租户与用户隔离() {
        repo.create(T, U, M);
        repo.create(new TenantId("t2"), U, M);
        repo.create(T, new UserId("u2"), M);

        assertThat(repo.findByUser(T, U, 0, 10)).hasSize(1);
        assertThat(repo.findByUser(new TenantId("t2"), U, 0, 10)).hasSize(1);
        assertThat(repo.findByUser(T, new UserId("u2"), 0, 10)).hasSize(1);
    }

    @Test
    void delete移除会话() {
        Session s = repo.create(T, U, M);
        repo.delete(s.id());
        assertThat(repo.load(s.id())).isNull();
        assertThat(repo.findByUser(T, U, 0, 10)).isEmpty();
    }

    @Test
    void delete不存在id不抛异常() {
        repo.delete(new SessionId("ghost")); // 无异常
    }

    @Test
    void 多线程并发save_线程安全() throws Exception {
        Session s = repo.create(T, U, M);
        int n = 50;
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> repo.save(s.append(new UserMessage("m" + idx))));
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        // 最终某次 save 胜出，load 返回有效 Session（历史 1 条，线程安全无损坏）
        Session loaded = repo.load(s.id());
        assertThat(loaded).isNotNull();
        assertThat(loaded.history()).hasSize(1);
    }
}
