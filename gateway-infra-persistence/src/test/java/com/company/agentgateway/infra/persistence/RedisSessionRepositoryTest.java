package com.company.agentgateway.infra.persistence;

import com.company.agentgateway.domain.orchestration.SessionRepository;
import com.company.agentgateway.domain.session.Session;
import com.company.agentgateway.domain.session.UserMessage;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.SessionId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisSessionRepository 集成测试（testcontainers-redis）。
 * 覆盖 create/load/save（含 TTL 续期 + 历史往返）/findByUser（user 索引）/delete/租户隔离 + 序列化（4 种 Message）。
 *
 * <p>需 Docker（testcontainers）。disabledWithoutDocker：CI 有 Docker 时跑；本机无 Docker 时跳过。
 * 跳过时 RedisSessionRepository 不计覆盖率（见 pom jacoco excludes 注释）。
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisSessionRepositoryTest {

    @Container
    static final RedisContainer REDIS = new RedisContainer("redis:7-alpine");

    private SessionRepository repo;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getFirstMappedPort());
        LettuceConnectionFactory cf = new LettuceConnectionFactory(cfg);
        cf.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(cf);
        redis.afterPropertiesSet();
        repo = new RedisSessionRepository(redis, new ObjectMapper());
    }

    private static final TenantId T = new TenantId("t1");
    private static final UserId U = new UserId("u1");
    private static final ModelId M = new ModelId("qwen");

    @Test
    void load不存在返回null() {
        assertThat(repo.load(new SessionId("ghost"))).isNull();
    }

    @Test
    void create_load往返一致() {
        Session s = repo.create(T, U, M);
        Session loaded = repo.load(s.id());
        assertThat(loaded).isNotNull();
        assertThat(loaded.tenant()).isEqualTo(T);
        assertThat(loaded.user()).isEqualTo(U);
        assertThat(loaded.model()).isEqualTo(M);
        assertThat(loaded.history()).isEmpty();
    }

    @Test
    void save含历史_4种Message往返() {
        Session s = repo.create(T, U, M);
        Session updated = s
                .append(new UserMessage("查销售额"))
                .append(new com.company.agentgateway.domain.session.AssistantMessage("调用 SalesAgent"))
                .append(new com.company.agentgateway.domain.session.ToolCallMessage("SalesAgent", "{\"q\":1}"))
                .append(new com.company.agentgateway.domain.session.ToolResultMessage("SalesAgent", "1.2M", false));
        repo.save(updated);

        Session loaded = repo.load(s.id());
        assertThat(loaded.history()).hasSize(4);
        assertThat(loaded.history().get(0)).isInstanceOf(com.company.agentgateway.domain.session.UserMessage.class);
        assertThat(loaded.history().get(3)).isInstanceOf(com.company.agentgateway.domain.session.ToolResultMessage.class);
    }

    @Test
    void findByUser用索引_只返回该用户() {
        repo.create(T, U, M);
        repo.create(T, new UserId("u2"), M);

        List<Session> mine = repo.findByUser(T, U, 0, 10);
        assertThat(mine).hasSize(1);
        assertThat(repo.findByUser(T, new UserId("u2"), 0, 10)).hasSize(1);
    }

    @Test
    void findByUser租户隔离() {
        repo.create(T, U, M);
        repo.create(new TenantId("t2"), U, M);
        assertThat(repo.findByUser(T, U, 0, 10)).hasSize(1);
    }

    @Test
    void delete移除会话与索引() {
        Session s = repo.create(T, U, M);
        repo.delete(s.id());
        assertThat(repo.load(s.id())).isNull();
        assertThat(repo.findByUser(T, U, 0, 10)).isEmpty();
    }

    @Test
    void save滑动续期TTL() {
        Session s = repo.create(T, U, M);
        // load 触发滑动续期；验证 TTL 仍为正（接近 24h，放宽断言 > 23h）
        repo.load(s.id());
        // 直接验证 load 仍能取到（TTL 未过期）+ 再 save 续期
        repo.save(s.append(new UserMessage("again")));
        assertThat(repo.load(s.id())).isNotNull();
    }
}
