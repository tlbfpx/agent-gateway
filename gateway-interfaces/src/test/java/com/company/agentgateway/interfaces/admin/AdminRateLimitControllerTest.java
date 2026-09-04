package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.audit.AuditRepository.AuditLog;
import com.company.agentgateway.domain.shared.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AdminRateLimitController 单测：5 维配额聚合 + 429 事件，
 * 契约与前端 ratelimit.ts 对齐。
 */
class AdminRateLimitControllerTest {

    private AdminMetricsControllerTest.InMemoryTestAudit repo;
    private AdminRateLimitController controller;

    @BeforeEach
    void setUp() {
        repo = new AdminMetricsControllerTest.InMemoryTestAudit();
        controller = new AdminRateLimitController(repo);
    }

    private void chat(String actor, String resource, Instant when) {
        repo.append(new AuditLog("c-" + System.nanoTime(), new TenantId("au"), actor,
                AuditLog.ActorType.HUMAN, AuditRepository.AuditEventType.SESSION_CHAT,
                when, "model", resource, "invoke", AuditLog.Result.SUCCESS, null));
    }

    private void exceeded(String actor, Instant when) {
        repo.append(new AuditLog("x-" + System.nanoTime(), new TenantId("au"), actor,
                AuditLog.ActorType.HUMAN, AuditRepository.AuditEventType.RATE_LIMIT_EXCEEDED,
                when, "ratelimit", "qps", "exceeded", AuditLog.Result.FAILURE, "too many requests"));
    }

    @Test
    void quotasAggregateFiveDimensions() {
        Instant now = Instant.now();
        chat("alice@au", "gpt-4o", now);
        chat("alice@au", "gpt-4o", now);
        chat("bob@au", "claude", now);

        List<Map<String, Object>> rows = controller.quotas("k", "au", "5m");

        // tenant=au(1) + user=alice,bob(2) + agent=gpt-4o,claude(2) + token-daily(1) = 6 行
        assertThat(rows).hasSize(6);
        Map<String, Object> alice = rows.stream()
                .filter(r -> "user".equals(r.get("dim")) && "alice@au".equals(r.get("id")))
                .findFirst().orElseThrow();
        assertThat(alice.get("limit")).isEqualTo(10L);
        // 2 次 / 300 秒 → 0.01 QPS（两位小数舍入）
        assertThat((double) alice.get("current")).isEqualTo(0.01);

        Map<String, Object> gpt = rows.stream()
                .filter(r -> "agent".equals(r.get("dim")) && "gpt-4o".equals(r.get("id")))
                .findFirst().orElseThrow();
        assertThat((double) gpt.get("current")).isEqualTo(2.0); // agent 维度=并发计数不折算

        Map<String, Object> td = rows.stream()
                .filter(r -> "token-daily".equals(r.get("dim"))).findFirst().orElseThrow();
        assertThat(td.get("id")).isEqualTo("au");
        assertThat((double) td.get("current")).isEqualTo(3 * 1500.0); // 3 次 × 1500
        assertThat(td.get("limit")).isEqualTo(1_000_000L);
    }

    @Test
    void blockedCountFromExceededEvents() {
        Instant now = Instant.now();
        chat("alice@au", "gpt-4o", now);
        exceeded("alice@au", now);
        exceeded("alice@au", now.minusSeconds(60));

        List<Map<String, Object>> rows = controller.quotas("k", "au", "5m");
        Map<String, Object> alice = rows.stream()
                .filter(r -> "user".equals(r.get("dim")) && "alice@au".equals(r.get("id")))
                .findFirst().orElseThrow();
        assertThat(alice.get("blocked")).isEqualTo(2L);
        assertThat((String) alice.get("lastBlockedAt")).isNotBlank();
    }

    @Test
    void quotasEmptyWhenNoTraffic() {
        List<Map<String, Object>> rows = controller.quotas("k", "au", "5m");
        // 仅 token-daily 一行（恒存在，0 值）
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("dim")).isEqualTo("token-daily");
        assertThat((double) rows.get(0).get("current")).isEqualTo(0.0);
    }

    @Test
    void eventsReturnExceededSortedByTimeDesc() {
        Instant now = Instant.now();
        exceeded("alice@au", now);
        exceeded("bob@au", now.minusSeconds(120));

        List<Map<String, Object>> events = controller.events("k", "au", "24h");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("id2")).isEqualTo("alice@au"); // 最近在前
        assertThat(events.get(1).get("id2")).isEqualTo("bob@au");
        assertThat(events.get(0).get("reason")).isEqualTo("exceeded");
    }

    @Test
    void eventsExcludeOtherTypes() {
        Instant now = Instant.now();
        chat("alice@au", "gpt-4o", now); // 非 429 事件
        assertThat(controller.events("k", "au", "24h")).isEmpty();
    }
}
