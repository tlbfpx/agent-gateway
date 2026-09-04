package com.company.agentgateway.infra.persistence;

import com.company.agentgateway.domain.session.Session;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.SessionId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisSessionRepository 序列化单元测试（无需 Docker/Redis）。
 * 覆盖 serialize/deserialize 往返 + 4 种 Message + StoredSession 映射，
 * 保证 RedisSessionRepository 的核心逻辑有覆盖率（不依赖 testcontainers 集成测试）。
 */
class RedisSessionRepositorySerializationTest {

    private final RedisSessionRepository repo = new RedisSessionRepository(null, new ObjectMapper());

    @Test
    void serializeDeserialize往返一致_空历史() {
        Session s = newSession("s1", List.of());
        String json = repo.serialize(s);
        Session restored = repo.deserialize(json);

        assertThat(restored).isNotNull();
        assertThat(restored.id()).isEqualTo(s.id());
        assertThat(restored.tenant()).isEqualTo(s.tenant());
        assertThat(restored.user()).isEqualTo(s.user());
        assertThat(restored.model()).isEqualTo(s.model());
        assertThat(restored.createdAt()).isEqualTo(s.createdAt());
        assertThat(restored.lastActiveAt()).isEqualTo(s.lastActiveAt());
        assertThat(restored.history()).isEmpty();
    }

    @Test
    void serializeDeserialize_4种Message往返() {
        Session s = newSession("s2", List.of(
                new com.company.agentgateway.domain.session.UserMessage("查销售额"),
                new com.company.agentgateway.domain.session.AssistantMessage("调用 SalesAgent"),
                new com.company.agentgateway.domain.session.ToolCallMessage("SalesAgent", "{\"q\":1}"),
                new com.company.agentgateway.domain.session.ToolResultMessage("SalesAgent", "1.2M", false)
        ));
        String json = repo.serialize(s);
        Session restored = repo.deserialize(json);

        assertThat(restored.history()).hasSize(4);
        assertThat(restored.history().get(0)).isInstanceOf(com.company.agentgateway.domain.session.UserMessage.class);
        assertThat(restored.history().get(1)).isInstanceOf(com.company.agentgateway.domain.session.AssistantMessage.class);
        assertThat(restored.history().get(2)).isInstanceOf(com.company.agentgateway.domain.session.ToolCallMessage.class);
        assertThat(restored.history().get(3)).isInstanceOf(com.company.agentgateway.domain.session.ToolResultMessage.class);
        // 字段值
        var tc = (com.company.agentgateway.domain.session.ToolCallMessage) restored.history().get(2);
        assertThat(tc.agentName()).isEqualTo("SalesAgent");
        assertThat(tc.argsJson()).isEqualTo("{\"q\":1}");
        var tr = (com.company.agentgateway.domain.session.ToolResultMessage) restored.history().get(3);
        assertThat(tr.slimmed()).isFalse();
    }

    @Test
    void deserialize坏json返回null() {
        assertThat(repo.deserialize("{not valid json")).isNull();
    }

    @Test
    void deserialize未知messageType抛异常() {
        // 构造含未知 type 的 json
        String bad = "{\"id\":\"s\",\"tenant\":\"t\",\"user\":\"u\",\"model\":\"m\","
                + "\"createdAtEpochMilli\":0,\"lastActiveAtEpochMilli\":0,"
                + "\"history\":[{\"type\":\"ghost\",\"content\":\"x\",\"agentName\":null,\"argsJson\":null,\"slimmed\":false}]}";
        // StoredSession.toDomain → MessageDto.toDomain 抛 IllegalArgumentException（被 deserialize 吞 → null）
        assertThat(repo.deserialize(bad)).isNull();
    }

    @Test
    void StoredSessionFromToDomain双向() {
        Session s = newSession("s3", List.of(new com.company.agentgateway.domain.session.UserMessage("hi")));
        var stored = RedisSessionRepository.StoredSession.from(s);
        Session restored = stored.toDomain();
        assertThat(restored).isEqualTo(s);
    }

    private Session newSession(String id, List<com.company.agentgateway.domain.session.Message> history) {
        Instant now = Instant.parse("2026-08-14T00:00:00Z");
        return new Session(new SessionId(id), new TenantId("t1"), new UserId("u1"),
                new ModelId("qwen"), now, now, history);
    }
}
