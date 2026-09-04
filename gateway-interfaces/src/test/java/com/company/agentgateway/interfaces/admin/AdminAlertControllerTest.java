package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.audit.AuditRepository.AuditLog;
import com.company.agentgateway.domain.observability.AlertStore;
import com.company.agentgateway.domain.observability.AlertStore.AlertRecord;
import com.company.agentgateway.domain.observability.AlertStore.AlertRule;
import com.company.agentgateway.domain.shared.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AdminAlertController 单测(spec 2026-08-19 §5.5 契约):规则 CRUD + 告警流 + 认领/静默。
 * AlertStore 用内存桩(真实 PG 实现由 persistence 集成测试覆盖)。
 */
class AdminAlertControllerTest {

    private AdminMetricsControllerTest.InMemoryTestAudit repo;
    private InMemoryAlertStore store;
    private AdminAlertController controller;

    @BeforeEach
    void setUp() {
        repo = new AdminMetricsControllerTest.InMemoryTestAudit();
        store = new InMemoryAlertStore();
        controller = new AdminAlertController(repo, store);
    }

    private AdminAlertController.RuleDto ruleInput(String name) {
        return new AdminAlertController.RuleDto(name, "chat.errors", "GT", 5.0,
                300, 30, null, "critical", true);
    }

    @Test
    void createWithMinimalBodyUsesDefaults() {
        // 只传必需字段:窗口/静默/严重级别走默认值
        AlertRule created = controller.create("k", "au",
                new AdminAlertController.RuleDto("极简规则", "chat.errors", null, null, null, null, null, null, null));
        assertThat(created.windowSeconds()).isEqualTo(300);
        assertThat(created.silenceMinutes()).isEqualTo(30);
        assertThat(created.severity()).isEqualTo("warning");
        assertThat(created.operator()).isEqualTo(AlertRule.Operator.GT);
    }

    @Test
    void crudLifecycle() {
        AlertRule created = controller.create("k", "au", ruleInput("错误数超5"));
        String id = created.id();
        assertThat(id).isNotBlank();
        assertThat(created.name()).isEqualTo("错误数超5");
        assertThat(created.metricName()).isEqualTo("chat.errors");
        assertThat(created.operator()).isEqualTo(AlertRule.Operator.GT);

        assertThat(controller.rules("k", "au")).hasSize(1);

        // update(改阈值 + 关闭;未覆盖字段保留)
        AlertRule updated = controller.update("k", "au", id,
                new AdminAlertController.RuleDto(null, null, null, 10.0, 0, 0, null, null, false));
        assertThat(updated.threshold()).isEqualTo(10.0);
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.name()).isEqualTo("错误数超5");

        assertThat(controller.delete("k", "au", id)).containsEntry("deleted", id);
        assertThat(controller.rules("k", "au")).isEmpty();
    }

    @Test
    void createWithoutNameThrows400() {
        assertThatThrownBy(() -> controller.create("k", "au",
                new AdminAlertController.RuleDto(null, "chat.errors", "GT", 5.0, 300, 30, null, null, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void updateWithNullFieldsKeepsExisting() {
        AlertRule created = controller.create("k", "au", ruleInput("r1"));
        AlertRule updated = controller.update("k", "au", created.id(),
                new AdminAlertController.RuleDto(null, null, null, null, null, null, null, null, null));
        assertThat(updated.name()).isEqualTo("r1");
        assertThat(updated.threshold()).isEqualTo(5.0);
        assertThat(updated.enabled()).isTrue();
    }

    @Test
    void updateUnknownRuleThrows404() {
        assertThatThrownBy(() -> controller.update("k", "au", "ar-999",
                new AdminAlertController.RuleDto("r", null, null, 1.0, 0, 0, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void deleteUnknownRuleThrows404() {
        assertThatThrownBy(() -> controller.delete("k", "au", "ar-999"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void ruleMutationsAppendAudit() {
        controller.create("k", "au", ruleInput("r1"));
        List<AuditLog> audits = repo.query(new TenantId("au"), null, null, null, 100);
        assertThat(audits.stream().anyMatch(a -> "alert-rule-create".equals(a.action()))).isTrue();
    }

    @Test
    void alertFlowQueriesFiringFirst() {
        store.seedFiring("al-1", "critical");
        store.seedResolved("al-2", "warning");
        List<AlertRecord> flow = controller.alerts("k", "au", null, null, 100);
        assertThat(flow).hasSize(2);
        assertThat(flow.get(0).state()).isEqualTo("firing");

        List<AlertRecord> firingOnly = controller.alerts("k", "au", "firing", null, 100);
        assertThat(firingOnly).hasSize(1);
        assertThat(firingOnly.get(0).id()).isEqualTo("al-1");
    }

    @Test
    void ackRecordsClaimerAndNote() {
        store.seedFiring("al-3", "warning");
        AlertRecord acked = controller.ack("k", "au", "al-3",
                new AdminAlertController.AckDto("ops-bob", "处理中"));
        assertThat(acked.claimedBy()).isEqualTo("ops-bob");
        assertThat(acked.note()).isEqualTo("处理中");
        // 缺省认领人
        AlertRecord acked2 = controller.ack("k", "au", "al-3", null);
        assertThat(acked2.claimedBy()).isEqualTo("admin");
    }

    @Test
    void silenceAppendsNote() {
        store.seedFiring("al-4", "warning");
        AlertRecord silenced = controller.silence("k", "au", "al-4",
                new AdminAlertController.AckDto(null, "夜间静默"));
        assertThat(silenced.note()).contains("silenced").contains("夜间静默");
        assertThat(silenced.state()).isEqualTo("firing");  // 状态不变
    }

    @Test
    void noStorageReturns503() {
        AdminAlertController bare = new AdminAlertController(repo, null);
        assertThatThrownBy(() -> bare.rules("k", "au"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503");
    }

    @Test
    void eventsDerivedFromRateLimitAudit() {
        repo.append(new AuditLog("x-1", new TenantId("au"), "alice",
                AuditLog.ActorType.HUMAN, AuditRepository.AuditEventType.RATE_LIMIT_EXCEEDED,
                Instant.now(), "ratelimit", "qps", "exceeded", AuditLog.Result.FAILURE, "qps 12 > 10"));

        List<Map<String, Object>> events = controller.events("k", "au", "24h");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("message")).isEqualTo("qps 12 > 10");
    }

    /** 内存 AlertStore 桩(仅本测试用;生产为 PgAlertStore)。 */
    static class InMemoryAlertStore implements AlertStore {
        final Map<String, AlertRule> rules = new ConcurrentHashMap<>();
        final List<AlertRecord> alerts = java.util.Collections.synchronizedList(new ArrayList<>());
        final AtomicLong seq = new AtomicLong();

        void seedFiring(String id, String severity) {
            alerts.add(new AlertRecord(id, "r-" + id, severity, "firing", "dk-" + id,
                    Map.of(), Instant.now(), Instant.now(), 1, 1.0, 1.0, null, null, null));
        }

        void seedResolved(String id, String severity) {
            alerts.add(new AlertRecord(id, "r-" + id, severity, "resolved", "dk-" + id,
                    Map.of(), Instant.now(), Instant.now(), 1, 1.0, 1.0, null, null, Instant.now()));
        }

        @Override
        public AlertRule saveRule(AlertRule rule) {
            String id = rule.id() == null ? "ar-" + seq.incrementAndGet() : rule.id();
            AlertRule saved = new AlertRule(id, rule.name(), rule.metricName(), rule.operator(),
                    rule.threshold(), rule.windowSeconds(), rule.silenceMinutes(),
                    rule.dedupKeyTpl(), rule.severity(), rule.enabled(),
                    rule.createdAt() == null ? Instant.now() : rule.createdAt(), Instant.now());
            rules.put(id, saved);
            return saved;
        }

        @Override
        public Optional<AlertRule> getRule(String id) { return Optional.ofNullable(rules.get(id)); }

        @Override
        public List<AlertRule> listRules(boolean enabledOnly) {
            return rules.values().stream()
                    .filter(r -> !enabledOnly || r.enabled())
                    .sorted(Comparator.comparing(AlertRule::createdAt))
                    .toList();
        }

        @Override
        public boolean deleteRule(String id) { return rules.remove(id) != null; }

        @Override
        public AlertRecord insertFiring(AlertRecord alert) {
            String id = alert.id() == null ? "al-" + seq.incrementAndGet() : alert.id();
            AlertRecord saved = new AlertRecord(id, alert.ruleId(), alert.severity(), "firing",
                    alert.dedupKey(), alert.labels(), alert.firstFiredAt(), alert.recentlyTriggeredAt(),
                    alert.triggerCount(), alert.observedValue(), alert.threshold(),
                    alert.claimedBy(), alert.note(), null);
            alerts.add(saved);
            return saved;
        }

        @Override
        public Optional<AlertRecord> findLatestByDedupKey(String dedupKey) {
            return alerts.stream()
                    .filter(a -> a.dedupKey().equals(dedupKey))
                    .max(Comparator.comparing(AlertRecord::recentlyTriggeredAt));
        }

        @Override
        public AlertRecord update(AlertRecord alert) {
            for (int i = 0; i < alerts.size(); i++) {
                if (alerts.get(i).id().equals(alert.id())) {
                    alerts.set(i, alert);
                    return alert;
                }
            }
            throw new IllegalArgumentException("not found: " + alert.id());
        }

        @Override
        public List<AlertRecord> queryAlerts(String state, String severity, int limit) {
            return alerts.stream()
                    .filter(a -> state == null || state.isBlank() || a.state().equals(state))
                    .filter(a -> severity == null || severity.isBlank() || a.severity().equals(severity))
                    // firing 优先(与 PgAlertStore 的 ORDER BY (state='firing') DESC 语义一致)
                    .sorted(Comparator.<AlertRecord, Integer>comparing(a -> "firing".equals(a.state()) ? 0 : 1)
                            .thenComparing(Comparator.comparing(AlertRecord::recentlyTriggeredAt).reversed()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public Optional<AlertRecord> get(String id) {
            return alerts.stream().filter(a -> a.id().equals(id)).findFirst();
        }
    }
}
