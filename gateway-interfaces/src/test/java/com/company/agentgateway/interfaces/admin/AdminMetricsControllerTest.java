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
 * AdminMetricsController 单测：聚合逻辑从 InMemory 审计流
 * 产出 overview / usage / top，契约与前端 usage.ts 对齐。
 */
class AdminMetricsControllerTest {

    private InMemoryTestAudit repo;
    private AdminMetricsController controller;

    @BeforeEach
    void setUp() {
        repo = new InMemoryTestAudit();
        controller = new AdminMetricsController(repo);
    }

    private void log(String actor, String resourceId, AuditLog.Result result, Instant when) {
        repo.append(new AuditLog(
                "e-" + System.nanoTime(), new TenantId("au"), actor,
                AuditLog.ActorType.HUMAN, AuditRepository.AuditEventType.SESSION_CHAT,
                when, "model", resourceId, "invoke", result, null));
    }

    @Test
    void overviewCountsRequestsAndErrorRate() {
        Instant now = Instant.now();
        log("alice@au", "gpt-4o", AuditLog.Result.SUCCESS, now);
        log("bob@au", "gpt-4o", AuditLog.Result.SUCCESS, now);
        log("bob@au", "claude", AuditLog.Result.FAILURE, now);
        log("alice@au", "gpt-4o", AuditLog.Result.SUCCESS, now.minusSeconds(3600 * 25)); // 窗口外

        Map<String, Object> o = controller.overview("k", "au");
        assertThat(o.get("requests24h")).isEqualTo(3L);
        assertThat((double) o.get("errorRate")).isEqualTo(1.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void overviewEmptyWhenNoTraffic() {
        Map<String, Object> o = controller.overview("k", "au");
        assertThat(o.get("requests24h")).isEqualTo(0L);
        assertThat((double) o.get("errorRate")).isEqualTo(0.0);
    }

    @Test
    void usageBucketsByHourFor24h() {
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            log("alice@au", "gpt-4o", AuditLog.Result.SUCCESS, now);
        }
        log("alice@au", "gpt-4o", AuditLog.Result.FAILURE, now);

        List<Map<String, Object>> series = controller.usage("k", "au", "24h");
        assertThat(series).hasSize(24);
        long total = series.stream().mapToLong(p -> (long) p.get("n")).sum();
        long errors = series.stream().mapToLong(p -> (long) p.get("err")).sum();
        assertThat(total).isEqualTo(6);
        assertThat(errors).isEqualTo(1);
    }

    @Test
    void topByModelRanksDescending() {
        Instant now = Instant.now();
        log("a@au", "gpt-4o", AuditLog.Result.SUCCESS, now);
        log("b@au", "gpt-4o", AuditLog.Result.SUCCESS, now);
        log("b@au", "gpt-4o", AuditLog.Result.FAILURE, now);
        log("c@au", "claude", AuditLog.Result.SUCCESS, now);

        List<Map<String, Object>> top = controller.top("k", "au", "model", "24h", 10);
        assertThat(top).hasSize(2);
        assertThat(top.get(0).get("id")).isEqualTo("gpt-4o");
        assertThat(top.get(0).get("n")).isEqualTo(3L);
        assertThat(top.get(0).get("err")).isEqualTo(1L);
    }

    @Test
    void topByTenantExtractsDomainFromActor() {
        Instant now = Instant.now();
        log("alice@tenant-b", "gpt-4o", AuditLog.Result.SUCCESS, now);
        log("bob@tenant-b", "gpt-4o", AuditLog.Result.SUCCESS, now);
        log("carol@tenant-c", "gpt-4o", AuditLog.Result.SUCCESS, now);

        List<Map<String, Object>> top = controller.top("k", "au", "tenant", "24h", 10);
        assertThat(top.get(0).get("id")).isEqualTo("tenant-b");
        assertThat(top.get(0).get("n")).isEqualTo(2L);
    }

    @Test
    void topRespectsLimit() {
        Instant now = Instant.now();
        log("a@au", "m1", AuditLog.Result.SUCCESS, now);
        log("a@au", "m2", AuditLog.Result.SUCCESS, now);

        List<Map<String, Object>> top = controller.top("k", "au", "model", "24h", 1);
        assertThat(top).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void costAggregatesFourDimensionsSortedByCost() {
        Instant now = Instant.now();
        // gpt-4o 三次（tenant-b 两次 + tenant-c 一次），claude 一次失败
        log("a@tenant-b", "gpt-4o", AuditLog.Result.SUCCESS, now);
        log("b@tenant-b", "gpt-4o", AuditLog.Result.SUCCESS, now);
        log("c@tenant-c", "gpt-4o", AuditLog.Result.SUCCESS, now);
        log("c@tenant-c", "claude-3.7", AuditLog.Result.FAILURE, now);

        Map<String, Object> r = controller.cost("k", "au", "24h");

        Map<String, Object> total = (Map<String, Object>) r.get("total");
        assertThat(total.get("calls")).isEqualTo(4L);
        assertThat(total.get("tokens")).isEqualTo(4L * 1500L);
        assertThat(total.get("errors")).isEqualTo(1L);

        List<Map<String, Object>> byTenant = (List<Map<String, Object>>) r.get("byTenant");
        assertThat(byTenant).hasSize(2);
        // tenant-c 成本最高排前：gpt-4o + claude-3.7（单价更高）> tenant-b 的 2×gpt-4o
        assertThat(byTenant.get(0).get("id")).isEqualTo("tenant-c");
        assertThat(byTenant.get(0).get("calls")).isEqualTo(2L);
        assertThat(byTenant.get(1).get("id")).isEqualTo("tenant-b");
        assertThat(byTenant.get(1).get("calls")).isEqualTo(2L);

        List<Map<String, Object>> byModel = (List<Map<String, Object>>) r.get("byModel");
        assertThat(byModel.get(0).get("id")).isEqualTo("gpt-4o");
        assertThat(byModel.get(0).get("calls")).isEqualTo(3L);

        List<Map<String, Object>> byKey = (List<Map<String, Object>>) r.get("byKey");
        assertThat(byKey).hasSize(3);

        List<Map<String, Object>> byDay = (List<Map<String, Object>>) r.get("byDay");
        assertThat(byDay).hasSize(1);

        assertThat(r.get("live")).isEqualTo(true);
        assertThat(r.get("range")).isEqualTo("24h");
    }

    @Test
    @SuppressWarnings("unchecked")
    void costEmptyWhenNoTraffic() {
        Map<String, Object> r = controller.cost("k", "au", "24h");
        Map<String, Object> total = (Map<String, Object>) r.get("total");
        assertThat(total.get("calls")).isEqualTo(0L);
        assertThat(total.get("costCny")).isEqualTo(0.0);
        assertThat((List<Map<String, Object>>) r.get("byModel")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void costExcludesOlderThanRange() {
        Instant now = Instant.now();
        log("a@au", "gpt-4o", AuditLog.Result.SUCCESS, now);
        log("a@au", "gpt-4o", AuditLog.Result.SUCCESS, now.minusSeconds(3600 * 48)); // 48h 前，超出 24h 窗口

        Map<String, Object> r = controller.cost("k", "au", "24h");
        Map<String, Object> total = (Map<String, Object>) r.get("total");
        assertThat(total.get("calls")).isEqualTo(1L);
    }

    @Test
    void costPerKPriceMatchesContractTable() {
        // gpt-4o: 0.018*0.6 + 0.072*0.4 = 0.0396 元/千 token；1500 token = 0.0594 → round2 = 0.06
        Instant now = Instant.now();
        log("a@au", "gpt-4o", AuditLog.Result.SUCCESS, now);

        Map<String, Object> r = controller.cost("k", "au", "24h");
        @SuppressWarnings("unchecked")
        Map<String, Object> total = (Map<String, Object>) r.get("total");
        assertThat(total.get("costCny")).isEqualTo(0.06);
    }

    /** 测试用内存实现（不依赖 infra 模块）。 */
    static class InMemoryTestAudit implements AuditRepository {
        private final java.util.List<AuditLog> logs = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override public void append(AuditLog log) { logs.add(log); }

        @Override
        public List<AuditLog> query(TenantId tenant, AuditEventType type, Instant from, Instant to, int limit) {
            return logs.stream()
                    .filter(l -> l.tenant().equals(tenant))
                    .filter(l -> type == null || l.eventType() == type)
                    .filter(l -> from == null || !l.timestamp().isBefore(from))
                    .filter(l -> to == null || !l.timestamp().isAfter(to))
                    .limit(limit > 0 ? limit : Long.MAX_VALUE)
                    .toList();
        }
    }
}
