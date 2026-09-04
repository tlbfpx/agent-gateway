package com.company.agentgateway.application.billing;

import com.company.agentgateway.domain.billing.AlertThreshold;
import com.company.agentgateway.domain.billing.Budget;
import com.company.agentgateway.domain.billing.BudgetRepository;
import com.company.agentgateway.domain.billing.BudgetType;
import com.company.agentgateway.domain.billing.InMemoryBudgetRepository;
import com.company.agentgateway.domain.iam.RbacChangeEvent;
import com.company.agentgateway.domain.iam.RbacChangePublisher;
import com.company.agentgateway.domain.observability.AlertStore;
import com.company.agentgateway.domain.observability.GatewayEvents;
import com.company.agentgateway.domain.shared.TenantId;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BudgetGuardTest {

    private final TenantId t = new TenantId("t1");

    private BudgetRepository repoWithBudget(BigDecimal daily, BigDecimal monthly, int pct) {
        BudgetRepository repo = new InMemoryBudgetRepository();
        repo.save(new Budget(t, null, BudgetType.MONEY,
                daily, monthly, BigDecimal.ZERO, BigDecimal.ZERO,
                new AlertThreshold(pct), false, null, null));
        return repo;
    }

    @Test
    void onUsageAccumulated_belowThreshold_doesNotTriggerAlert() {
        BudgetRepository repo = repoWithBudget(BigDecimal.TEN, BigDecimal.valueOf(100), 80);
        RbacChangePublisher pub = mock(RbacChangePublisher.class);
        BudgetGuard guard = new BudgetGuard(repo, pub);
        guard.onUsageAccumulated(t, new BigDecimal("5.0")); // 50% 未到 80%
        verifyNoInteractions(pub);
        assertThat(repo.findByTenant(t).orElseThrow().currentDailyUsed()).isEqualByComparingTo("5.0");
    }

    @Test
    void onUsageAccumulated_aboveThreshold_triggersAlert() {
        BudgetRepository repo = repoWithBudget(BigDecimal.TEN, BigDecimal.valueOf(100), 80);
        RbacChangePublisher pub = mock(RbacChangePublisher.class);
        AtomicReference<RbacChangeEvent> captured = new AtomicReference<>();
        doAnswer(inv -> { captured.set(inv.getArgument(0)); return null; }).when(pub).publish(any());
        BudgetGuard guard = new BudgetGuard(repo, pub);
        guard.onUsageAccumulated(t, new BigDecimal("8.5")); // 85% > 80%
        verify(pub, times(1)).publish(any(RbacChangeEvent.class));
        assertThat(captured.get().actor()).contains("BUDGET_EXCEEDED");
        assertThat(captured.get().actor()).contains("used=8.5");
        // alertSent 置 true（幂等）
        assertThat(repo.findByTenant(t).orElseThrow().alertSent()).isTrue();
    }

    @Test
    void onUsageAccumulated_secondCallAboveThreshold_doesNotReTrigger() {
        BudgetRepository repo = repoWithBudget(BigDecimal.TEN, BigDecimal.valueOf(100), 80);
        RbacChangePublisher pub = mock(RbacChangePublisher.class);
        BudgetGuard guard = new BudgetGuard(repo, pub);
        guard.onUsageAccumulated(t, new BigDecimal("8.5")); // 第 1 次：触发
        guard.onUsageAccumulated(t, new BigDecimal("1.0")); // 第 2 次：已发过，幂等
        verify(pub, times(1)).publish(any(RbacChangeEvent.class));
    }

    @Test
    void onUsageAccumulated_noBudgetForTenant_silentlyIgnored() {
        BudgetRepository repo = new InMemoryBudgetRepository(); // 空
        RbacChangePublisher pub = mock(RbacChangePublisher.class);
        BudgetGuard guard = new BudgetGuard(repo, pub);
        assertThatCode(() -> guard.onUsageAccumulated(new TenantId("t-unknown"), BigDecimal.ONE))
                .doesNotThrowAnyException();
        verifyNoInteractions(pub);
    }

    @Test
    void onUsageAccumulated_publisherFailure_doesNotPropagate() {
        BudgetRepository repo = repoWithBudget(BigDecimal.TEN, BigDecimal.valueOf(100), 80);
        RbacChangePublisher pub = mock(RbacChangePublisher.class);
        doThrow(new RuntimeException("redis down")).when(pub).publish(any());
        BudgetGuard guard = new BudgetGuard(repo, pub);
        // spec §GW-QUOTA-007 失败容错：不阻断
        assertThatCode(() -> guard.onUsageAccumulated(t, new BigDecimal("8.5")))
                .doesNotThrowAnyException();
    }

    @Test
    void onUsageAccumulated_zeroDailyLimit_skipsCheck() {
        BudgetRepository repo = repoWithBudget(BigDecimal.ZERO, BigDecimal.valueOf(100), 80); // 日限 0
        RbacChangePublisher pub = mock(RbacChangePublisher.class);
        BudgetGuard guard = new BudgetGuard(repo, pub);
        guard.onUsageAccumulated(t, BigDecimal.TEN);
        verifyNoInteractions(pub); // 未设上限 → 不告警
    }

    // ================= P1：AlertStore + Webhook 接线 =================

    /** 内存 AlertStore fake（足够覆盖 BudgetGuard 语义）。 */
    static class FakeAlertStore implements AlertStore {
        final java.util.List<AlertRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong();

        @Override public AlertRecord insertFiring(AlertRecord alert) {
            AlertRecord saved = new AlertRecord("al-" + seq.incrementAndGet(), alert.ruleId(),
                    alert.severity(), alert.state(), alert.dedupKey(), alert.labels(),
                    alert.firstFiredAt(), alert.recentlyTriggeredAt(), alert.triggerCount(),
                    alert.observedValue(), alert.threshold(), alert.claimedBy(), alert.note(),
                    alert.resolvedAt());
            records.add(saved);
            return saved;
        }
        @Override public Optional<AlertRecord> findLatestByDedupKey(String dedupKey) {
            return records.stream().filter(r -> r.dedupKey().equals(dedupKey))
                    .reduce((a, b) -> b);
        }
        @Override public AlertRecord update(AlertRecord alert) {
            records.removeIf(r -> r.id().equals(alert.id()));
            records.add(alert);
            return alert;
        }
        @Override public java.util.List<AlertRecord> queryAlerts(String state, String severity, int limit) {
            return records.stream().filter(r -> state == null || r.state().equals(state))
                    .filter(r -> severity == null || r.severity().equals(severity)).limit(limit).toList();
        }
        @Override public Optional<AlertRecord> get(String id) {
            return records.stream().filter(r -> r.id().equals(id)).findFirst();
        }
        @Override public AlertRule saveRule(AlertRule rule) { throw new UnsupportedOperationException(); }
        @Override public Optional<AlertRule> getRule(String id) { return Optional.empty(); }
        @Override public java.util.List<AlertRule> listRules(boolean enabledOnly) { return List.of(); }
        @Override public boolean deleteRule(String id) { return false; }
    }

    /** 捕获 GatewayEvents 推送（Webhook 桥接入口）。 */
    static class CapturingEvents implements GatewayEvents {
        final java.util.List<String> types = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public void publish(String type, java.util.Map<String, Object> payload) {
            types.add(type);
        }
    }

    @Test
    void p1_alertStore_达80阈值写warning告警并推Webhook() {
        BudgetRepository repo = repoWithBudget(BigDecimal.TEN, BigDecimal.valueOf(100), 80);
        FakeAlertStore store = new FakeAlertStore();
        CapturingEvents events = new CapturingEvents();
        BudgetGuard guard = new BudgetGuard(repo, mock(RbacChangePublisher.class), store, events);
        guard.onUsageAccumulated(t, new BigDecimal("8.5")); // 85%
        assertThat(store.records).hasSize(1);
        assertThat(store.records.get(0).severity()).isEqualTo("warning");
        assertThat(store.records.get(0).dedupKey()).isEqualTo("budget:t1:80");
        assertThat(store.records.get(0).state()).isEqualTo("firing");
        assertThat(events.types).containsExactly("budget.alert");
    }

    @Test
    void p1_alertStore_达100写critical_两级各一条() {
        BudgetRepository repo = repoWithBudget(BigDecimal.TEN, BigDecimal.valueOf(100), 80);
        FakeAlertStore store = new FakeAlertStore();
        BudgetGuard guard = new BudgetGuard(repo, mock(RbacChangePublisher.class), store,
                GatewayEvents.NOOP);
        guard.onUsageAccumulated(t, new BigDecimal("10.5")); // 105%
        assertThat(store.records).hasSize(2);
        assertThat(store.records).extracting(AlertStore.AlertRecord::severity)
                .containsExactlyInAnyOrder("warning", "critical");
        assertThat(store.records).extracting(AlertStore.AlertRecord::dedupKey)
                .containsExactlyInAnyOrder("budget:t1:80", "budget:t1:100");
    }

    @Test
    void p1_alertStore_重复用量不重复告警_去重() {
        BudgetRepository repo = repoWithBudget(BigDecimal.TEN, BigDecimal.valueOf(100), 80);
        FakeAlertStore store = new FakeAlertStore();
        BudgetGuard guard = new BudgetGuard(repo, mock(RbacChangePublisher.class), store,
                GatewayEvents.NOOP);
        guard.onUsageAccumulated(t, new BigDecimal("8.5")); // 触发 warning
        guard.onUsageAccumulated(t, new BigDecimal("0.5")); // 仍在 80+：去重
        guard.onUsageAccumulated(t, new BigDecimal("0.5")); // 90%：仍去重
        assertThat(store.records).hasSize(1); // 同 dedupKey 不重复插入
    }

    @Test
    void p1_alertStore_低于阈值不写告警() {
        BudgetRepository repo = repoWithBudget(BigDecimal.TEN, BigDecimal.valueOf(100), 80);
        FakeAlertStore store = new FakeAlertStore();
        BudgetGuard guard = new BudgetGuard(repo, mock(RbacChangePublisher.class), store,
                GatewayEvents.NOOP);
        guard.onUsageAccumulated(t, new BigDecimal("5.0")); // 50%
        assertThat(store.records).isEmpty();
    }
}
