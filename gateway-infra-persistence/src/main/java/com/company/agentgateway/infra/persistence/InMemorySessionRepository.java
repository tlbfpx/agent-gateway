package com.company.agentgateway.infra.persistence;

import com.company.agentgateway.domain.orchestration.SessionRepository;
import com.company.agentgateway.domain.session.Session;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.SessionId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * SessionRepository 内存实现（默认/无 Redis 时）。
 *
 * <p>spec §5.1：Redis 是热路径，但无 Redis 环境用内存兜底（开发/测试/小规模）。
 * 应用重启丢失历史（一期开发可接受；生产用 RedisSessionRepository）。
 *
 * <p>线程安全：ConcurrentHashMap + Session 不可变（save 替换整个 value）。
 * 多租户：findByUser 按 tenant+user 过滤；load/delete 无需 tenant（按 id，id 全局唯一不可猜）。
 */
public class InMemorySessionRepository implements SessionRepository {

    private final ConcurrentMap<SessionId, Session> store = new ConcurrentHashMap<>();

    @Override
    public Session load(SessionId id) {
        return store.get(id);
    }

    @Override
    public void save(Session session) {
        store.put(session.id(), session);
    }

    @Override
    public List<Session> findByUser(TenantId tenant, UserId user, int offset, int limit) {
        return store.values().stream()
                .filter(s -> s.tenant().equals(tenant) && s.user().equals(user))
                .sorted(Comparator.comparing(Session::lastActiveAt).reversed())
                .skip(Math.max(0, offset))
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public Session create(TenantId tenant, UserId user, ModelId model) {
        Instant now = Instant.now();
        Session session = new Session(
                new SessionId("sess-" + UUID.randomUUID()),
                tenant, user, model, now, now, List.of());
        store.put(session.id(), session);
        return session;
    }

    @Override
    public void delete(SessionId id) {
        store.remove(id);
    }
}
