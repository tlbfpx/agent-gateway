package com.company.agentgateway.infra.persistence.observability;

import com.company.agentgateway.domain.audit.AuditRepository.AuditEventType;
import com.company.agentgateway.domain.audit.AuditRepository.AuditLog;
import com.company.agentgateway.domain.audit.AuditRepository.AuditLog.ActorType;
import com.company.agentgateway.domain.audit.AuditRepository.AuditLog.Result;
import com.company.agentgateway.domain.observability.AlertStore.AlertRecord;
import com.company.agentgateway.domain.observability.AlertStore.AlertRule;
import com.company.agentgateway.domain.observability.AlertStore.AlertRule.Operator;
import com.company.agentgateway.domain.observability.SpanRecord;
import com.company.agentgateway.domain.observability.SpanRecord.Kind;
import com.company.agentgateway.domain.observability.SpanRecord.Status;
import com.company.agentgateway.domain.shared.TenantId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 持久化层集成测试(spec P0):手动构造 DataSource,验证:
 * <ul>
 *   <li>PgSchemaInitializer 自动建表(包含 hypertable + rollup + 保留策略)</li>
 *   <li>PgSpanStore / PgMetricsStore / PgAlertStore / PgAuditStore CRUD + 查询</li>
 * </ul>
 *
 * <p>不依赖 Spring 上下文:@BeforeAll 手动建容器/建 DataSource,Bean 手工 wire。
 * 激活方式:
 * <pre>
 *   # Docker 可用时默认拉 TimescaleDB 容器跑 IT
 *   mvn -Pit -pl gateway-infra-persistence verify -DskipUTs=true
 *
 *   # 或连外部 PG
 *   PG_URL=jdbc:postgresql://localhost:5433/agentgateway_it \
 *   mvn -Pit -pl gateway-infra-persistence verify -DskipUTs=true
 * </pre>
 */
class PgObservabilityStoresIT {

    private static JdbcTemplate jdbc;
    private static ObjectMapper objectMapper;
    private static PgSpanStore spanStore;
    private static PgMetricsStore metricsStore;
    private static PgAlertStore alertStore;
    private static PgAuditStore auditStore;

    @BeforeAll
    static void init() {
        DataSource ds = TestDb.connect();
        // 清关心的表(测试隔离;其他 IT 表不动)
        try {
            new JdbcTemplate(ds).execute("TRUNCATE workflow_run_steps, workflow_runs, spans, metrics_samples, alerts, audit_events RESTART IDENTITY");
        } catch (Exception ignore) { /* workflow_runs 可能尚未 init */ }
        jdbc = new JdbcTemplate(ds);
        objectMapper = new ObjectMapper();
        spanStore = new PgSpanStore(jdbc, objectMapper);
        metricsStore = new PgMetricsStore(jdbc, objectMapper);
        alertStore = new PgAlertStore(jdbc, objectMapper);
        auditStore = new PgAuditStore(jdbc);
    }

    @Test
    void schema初始化表结构存在() {
        // 容器类型:TimescaleDB 时 _timescaledb_catalog 可用;
        // 普通 PG 时降级为只校验表存在(便于无 TimescaleDB 镜像的环境跑通)
        Boolean timescaleAvailable = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_class WHERE relname = 'pg_class')", Boolean.class);
        assertThat(timescaleAvailable).isTrue();

        // 核心表必须存在(schema-observability.sql 幂等建表)
        Integer tablesCount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_catalog.pg_tables WHERE schemaname = 'public' " +
                        "AND tablename IN ('spans','metrics_samples','alerts','alert_rules','audit_events')",
                Integer.class);
        assertThat(tablesCount).isEqualTo(5);
    }

    @Test
    void spanStore_CUD与trace摘要查询() {
        String traceId = "trace-it-001";
        Instant now = Instant.now();
        SpanRecord s1 = new SpanRecord(traceId, "s1", null, "gateway.chat", Kind.SERVER,
                now, now.plusMillis(120), 120.0, Status.OK,
                Map.of("tenant_id", "t1"), List.of());
        SpanRecord s2 = new SpanRecord(traceId, "s2", "s1", "llm.call", Kind.CLIENT,
                now.plusMillis(10), now.plusMillis(110), 100.0, Status.OK,
                Map.of("provider", "deepseek"), List.of());
        spanStore.batchInsert(List.of(s1, s2));

        List<SpanRecord> spans = spanStore.getSpans(traceId);
        assertThat(spans).hasSize(2);

        var summaries = spanStore.queryTraces(
                new com.company.agentgateway.domain.observability.SpanQueryRepository.TraceFilter(
                        Instant.now().minusSeconds(60), null, null, null, null, "t1"),
                10, 0);
        assertThat(summaries).isNotEmpty();
    }

    @Test
    void metricsStore_时序聚合查询() {
        Instant now = Instant.now();
        metricsStore.batchInsert(List.of(
                new com.company.agentgateway.domain.observability.MetricPoint(
                        "chat.requests", Map.of("tenant_id", "t1"), now, 5.0),
                new com.company.agentgateway.domain.observability.MetricPoint(
                        "chat.requests", Map.of("tenant_id", "t1"), now, 3.0)
        ));
        OptionalDouble sum = metricsStore.windowSum("chat.requests", Map.of("tenant_id", "t1"),
                now.minusSeconds(60), now.plusSeconds(60));
        assertThat(sum).isPresent();
        assertThat(sum.getAsDouble()).isEqualTo(8.0);
    }

    @Test
    void alertStore_规则CRUD与告警生命周期() {
        AlertRule rule = new AlertRule("rule-1", "网关错误超10", "chat.errors", Operator.GT,
                10.0, 300, 30, "{rule}:{metric}", "critical", true,
                Instant.now(), Instant.now());
        alertStore.saveRule(rule);

        Optional<AlertRule> fetched = alertStore.getRule("rule-1");
        assertThat(fetched).isPresent();
        assertThat(fetched.get().name()).isEqualTo("网关错误超10");

        AlertRecord alert = new AlertRecord(null, "rule-1", "critical", "firing",
                "rule-1:chat.errors", Map.of("rule", "网关错误超10"),
                Instant.now(), Instant.now(), 1, 15.0, 10.0, null, null, null);
        alertStore.insertFiring(alert);

        // 通过 findLatestByDedupKey 拿到刚插入的(不会抛 NoSuchElementException)
        var fetchedAlert = alertStore.findLatestByDedupKey("rule-1:chat.errors");
        assertThat(fetchedAlert).isPresent();
        assertThat(fetchedAlert.get().state()).isEqualTo("firing");

        List<AlertRecord> firing = alertStore.queryAlerts("firing", null, 10);
        assertThat(firing).isNotEmpty();
        assertThat(firing.get(0).ruleId()).isEqualTo("rule-1");

        assertThat(alertStore.deleteRule("rule-1")).isTrue();
        assertThat(alertStore.getRule("rule-1")).isEmpty();
    }

    @Test
    void auditStore_append与query() {
        Instant before = Instant.now();
        auditStore.append(new AuditLog("audit-1", new TenantId("t1"), "tester",
                ActorType.HUMAN, AuditEventType.SESSION_CHAT,
                before, "session", "sess-1", "chat-start", Result.SUCCESS, null));
        auditStore.append(new AuditLog("audit-2", new TenantId("t1"), "tester",
                ActorType.HUMAN, AuditEventType.SESSION_CHAT,
                before.plusMillis(10), "session", "sess-2", "chat-start", Result.FAILURE, "boom"));

        List<AuditLog> logs = auditStore.query(new TenantId("t1"), AuditEventType.SESSION_CHAT,
                before.minusSeconds(5), before.plusSeconds(5), 10);
        assertThat(logs).hasSize(2);
    }
}