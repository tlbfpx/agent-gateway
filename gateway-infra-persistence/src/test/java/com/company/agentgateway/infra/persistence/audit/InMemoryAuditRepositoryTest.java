package com.company.agentgateway.infra.persistence.audit;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.shared.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAuditRepositoryTest {

    private static final TenantId T = new TenantId("t1");

    private final InMemoryAuditRepository repo = new InMemoryAuditRepository();

    private AuditRepository.AuditLog log(AuditRepository.AuditEventType type, AuditRepository.AuditLog.Result result) {
        return new AuditRepository.AuditLog(UUID.randomUUID().toString(), T, "u1",
                AuditRepository.AuditLog.ActorType.HUMAN, type, Instant.now(),
                "Session", "s1", "chat", result, null);
    }

    @Test
    void append_thenQuery_返回记录() {
        repo.append(log(AuditRepository.AuditEventType.SESSION_CHAT, AuditRepository.AuditLog.Result.SUCCESS));
        var results = repo.query(T, null, null, null, 10);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).eventType()).isEqualTo(AuditRepository.AuditEventType.SESSION_CHAT);
    }

    @Test
    void query_按类型筛选() {
        repo.append(log(AuditRepository.AuditEventType.SESSION_CHAT, AuditRepository.AuditLog.Result.SUCCESS));
        repo.append(log(AuditRepository.AuditEventType.AUTH_FAILED, AuditRepository.AuditLog.Result.FAILURE));
        var chats = repo.query(T, AuditRepository.AuditEventType.SESSION_CHAT, null, null, 10);
        assertThat(chats).hasSize(1);
        assertThat(chats.get(0).eventType()).isEqualTo(AuditRepository.AuditEventType.SESSION_CHAT);
    }

    @Test
    void query_按租户隔离() {
        repo.append(log(AuditRepository.AuditEventType.SESSION_CHAT, AuditRepository.AuditLog.Result.SUCCESS));
        var other = repo.query(new TenantId("t2"), null, null, null, 10);
        assertThat(other).isEmpty();
    }

    @Test
    void query_limit_限制返回数() {
        for (int i = 0; i < 5; i++) {
            repo.append(log(AuditRepository.AuditEventType.SESSION_CHAT, AuditRepository.AuditLog.Result.SUCCESS));
        }
        assertThat(repo.query(T, null, null, null, 3)).hasSize(3);
        assertThat(repo.query(T, null, null, null, 0)).hasSize(5); // 0=不限
    }

    @Test
    void append_only_不可修改删除() {
        var record = log(AuditRepository.AuditEventType.API_KEY_CREATE, AuditRepository.AuditLog.Result.SUCCESS);
        repo.append(record);
        // 接口无 update/delete 方法，只能 append（append-only 语义由接口设计保证）
        var results = repo.query(T, null, null, null, 10);
        assertThat(results).hasSize(1);
    }
}
