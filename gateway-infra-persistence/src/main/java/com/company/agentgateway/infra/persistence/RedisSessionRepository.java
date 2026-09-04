package com.company.agentgateway.infra.persistence;

import com.company.agentgateway.domain.orchestration.SessionRepository;
import com.company.agentgateway.domain.session.Message;
import com.company.agentgateway.domain.session.Session;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.SessionId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * SessionRepository Redis 实现（生产路径，spec §5.1）。
 *
 * <p>存储结构：
 * <ul>
 *   <li>会话：key={@code session:{tenant}:{sessionId}}，value=Session JSON（含历史 MessageDto 列表），TTL 24h 滑动续期</li>
 *   <li>用户索引：key={@code user:{tenant}:{userId}}，value=Set&lt;sessionId&gt;，供 findByUser</li>
 * </ul>
 *
 * <p>多租户：key 含 tenant 前缀防串。TTL：load/save 时 expire（滑动续期）。
 * 条件装配（@ConditionalOnProperty redis.addr）由 InfraPersistenceAutoConfiguration 控制。
 */
public class RedisSessionRepository implements SessionRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionRepository.class);
    private static final Duration TTL = Duration.ofHours(24);
    private static final String SESSION_KEY_PREFIX = "session:";
    private static final String USER_KEY_PREFIX = "user:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisSessionRepository(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    private static String sessionKey(Session session) {
        return sessionKey(session.tenant().value(), session.id().value());
    }

    private static String sessionKey(String tenant, String sessionId) {
        return SESSION_KEY_PREFIX + tenant + ":" + sessionId;
    }

    private static String userKey(TenantId tenant, UserId user) {
        return USER_KEY_PREFIX + tenant.value() + ":" + user.value();
    }

    @Override
    public Session load(SessionId id) {
        // id 不含 tenant 信息（SessionId 是不透明串），需遍历 tenant 前缀——一期约定：sessionId 编码 tenant
        // 简化：load 按 tenant 未知时，尝试用 id.value 作 key 后缀扫描不可行。
        // 设计调整：RedisSessionRepository 要求 load 时带 tenant？但端口签名 load(SessionId) 无 tenant。
        // 解法：session 存储时额外存 id→tenant 映射（key=id:{sessionId}，value=tenant）。
        String tenant = redis.opsForValue().get(idKey(id));
        if (tenant == null) {
            return null;
        }
        String json = redis.opsForValue().get(sessionKey(tenant, id.value()));
        if (json == null) {
            return null;
        }
        Session s = deserialize(json);
        if (s != null) {
            // 滑动续期
            redis.expire(sessionKey(tenant, id.value()), TTL.toSeconds(), TimeUnit.SECONDS);
        }
        return s;
    }

    @Override
    public void save(Session session) {
        String key = sessionKey(session);
        redis.opsForValue().set(key, serialize(session), TTL.toSeconds(), TimeUnit.SECONDS);
        // id→tenant 映射 + 用户索引
        redis.opsForValue().set(idKey(session.id()), session.tenant().value(), TTL.toSeconds(), TimeUnit.SECONDS);
        redis.opsForSet().add(userKey(session.tenant(), session.user()), session.id().value());
        redis.expire(userKey(session.tenant(), session.user()), TTL.toSeconds(), TimeUnit.SECONDS);
    }

    @Override
    public List<Session> findByUser(TenantId tenant, UserId user, int offset, int limit) {
        Set<String> ids = redis.opsForSet().members(userKey(tenant, user));
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .map(id -> {
                    String json = redis.opsForValue().get(sessionKey(tenant.value(), id));
                    return json == null ? null : deserialize(json);
                })
                .filter(java.util.Objects::nonNull)
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
        save(session);
        return session;
    }

    @Override
    public void delete(SessionId id) {
        String tenant = redis.opsForValue().get(idKey(id));
        if (tenant == null) {
            return;
        }
        Session s = deserialize(redis.opsForValue().get(sessionKey(tenant, id.value())));
        redis.delete(sessionKey(tenant, id.value()));
        redis.delete(idKey(id));
        if (s != null) {
            redis.opsForSet().remove(userKey(s.tenant(), s.user()), id.value());
        }
    }

    private static String idKey(SessionId id) {
        return "id:" + id.value();
    }

    // --- 序列化（Session 含 List<Message>，Message 经 MessageDto 转换）---
    // 静态方法 + package-private：可独立单测（无需 Docker/Redis），保证覆盖率不依赖集成测试。

    record StoredSession(String id, String tenant, String user, String model,
                         long createdAtEpochMilli, long lastActiveAtEpochMilli,
                         List<MessageDto> history) {
        Session toDomain() {
            List<Message> messages = history.stream().map(MessageDto::toDomain).toList();
            return new Session(
                    new SessionId(id), new TenantId(tenant), new UserId(user), new ModelId(model),
                    Instant.ofEpochMilli(createdAtEpochMilli), Instant.ofEpochMilli(lastActiveAtEpochMilli),
                    messages);
        }

        static StoredSession from(Session s) {
            List<MessageDto> dtos = s.history().stream().map(MessageDto::from).toList();
            return new StoredSession(
                    s.id().value(), s.tenant().value(), s.user().value(), s.model().value(),
                    s.createdAt().toEpochMilli(), s.lastActiveAt().toEpochMilli(), dtos);
        }
    }

    String serialize(Session session) {
        try {
            return objectMapper.writeValueAsString(StoredSession.from(session));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize session " + session.id(), e);
        }
    }

    Session deserialize(String json) {
        try {
            StoredSession stored = objectMapper.readValue(json, new TypeReference<>() {});
            return stored.toDomain();
        } catch (Exception e) {
            // JsonProcessingException + JsonMappingException + 可能的 IllegalArgumentException(未知 type)
            // 未知 messageType 时 MessageDto.toDomain 抛 IllegalArgumentException；坏 json 抛 JsonProcessingException。
            // 一律视为反序列化失败，返回 null（调用方降级）。
            log.error("Failed to deserialize session json: {}", e.getMessage());
            return null;
        }
    }
}
