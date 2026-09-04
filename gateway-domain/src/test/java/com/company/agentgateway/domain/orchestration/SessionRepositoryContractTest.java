package com.company.agentgateway.domain.orchestration;

import com.company.agentgateway.domain.session.Session;
import com.company.agentgateway.domain.session.UserMessage;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.SessionId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionRepository 端口契约测试：用最小内存实现验证端口签名可被实现 + 语义清晰。
 * （真实 InMemorySessionRepository 在 infra-persistence 模块测。）
 */
class SessionRepositoryContractTest {

    private static final TenantId T = new TenantId("t1");
    private static final UserId U = new UserId("u1");
    private static final ModelId M = new ModelId("qwen");

    @Test
    void 端口可实现_create_load_save_delete_findByUser() {
        SessionRepository repo = new MinimalInMemoryRepo();

        // create → load 一致
        Session created = repo.create(T, U, M);
        assertThat(created.tenant()).isEqualTo(T);
        assertThat(created.user()).isEqualTo(U);
        assertThat(created.model()).isEqualTo(M);
        assertThat(created.history()).isEmpty();

        // save（追加一条消息后）→ load 反映
        Session updated = created.append(new UserMessage("hello"));
        repo.save(updated);
        Session loaded = repo.load(created.id());
        assertThat(loaded.history()).hasSize(1);
        assertThat(loaded.lastActiveAt()).isAfterOrEqualTo(updated.createdAt());

        // findByUser
        List<Session> mine = repo.findByUser(T, U, 0, 10);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).id()).isEqualTo(created.id());

        // delete
        repo.delete(created.id());
        assertThat(repo.load(created.id())).isNull();
        assertThat(repo.findByUser(T, U, 0, 10)).isEmpty();
    }

    @Test
    void load不存在返回null() {
        SessionRepository repo = new MinimalInMemoryRepo();
        assertThat(repo.load(new SessionId("ghost"))).isNull();
    }

    @Test
    void findByUser租户隔离_只返回该租户的() {
        SessionRepository repo = new MinimalInMemoryRepo();
        repo.create(T, U, M);
        repo.create(new TenantId("t2"), U, M);

        assertThat(repo.findByUser(T, U, 0, 10)).hasSize(1);
        assertThat(repo.findByUser(new TenantId("t2"), U, 0, 10)).hasSize(1);
    }

    /** 最小内存实现，仅用于验证端口签名/语义。 */
    static final class MinimalInMemoryRepo implements SessionRepository {
        private final Map<SessionId, Session> store = new HashMap<>();
        private int counter = 0;

        @Override
        public Session load(SessionId id) { return store.get(id); }

        @Override
        public void save(Session session) { store.put(session.id(), session); }

        @Override
        public List<Session> findByUser(TenantId tenant, UserId user, int offset, int limit) {
            return store.values().stream()
                    .filter(s -> s.tenant().equals(tenant) && s.user().equals(user))
                    .skip(offset).limit(limit).toList();
        }

        @Override
        public Session create(TenantId tenant, UserId user, ModelId model) {
            counter++;
            Instant now = Instant.now();
            Session s = new Session(new SessionId("s-" + counter), tenant, user, model, now, now, List.of());
            store.put(s.id(), s);
            return s;
        }

        @Override
        public void delete(SessionId id) { store.remove(id); }
    }
}
