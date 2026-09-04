package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.billing.Budget;
import com.company.agentgateway.domain.billing.BudgetRepository;
import com.company.agentgateway.domain.billing.BudgetType;
import com.company.agentgateway.domain.billing.ExportFormat;
import com.company.agentgateway.domain.billing.UsageRecord;
import com.company.agentgateway.domain.iam.RbacChangeEvent;
import com.company.agentgateway.domain.iam.RbacChangePublisher;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D2 GW-QUOTA-008：AdminBillingController 端点契约 + 错误码映射（GW-4301~4306）。
 */
class AdminBillingControllerTest {

    private InMemoryBilling billing;
    private InMemoryBudgets budgets;
    private RecordingPublisher publisher;
    private AdminBillingController controller;

    @BeforeEach
    void setUp() {
        billing = new InMemoryBilling();
        budgets = new InMemoryBudgets();
        publisher = new RecordingPublisher();
        controller = new AdminBillingController(billing, budgets, publisher);
    }

    private UsageRecord rec(String id) {
        return new UsageRecord(id, new TenantId("t1"), new UserId("u1"),
                new ModelId("m1"), "agent", Instant.now(),
                100, 50, new BigDecimal("0.05"), new BigDecimal("0.001"), new BigDecimal("0.002"));
    }

    // --- GET /costs ---

    @Test
    void listCosts_returnsTenantRecords() {
        billing.records.add(rec("r1"));
        List<UsageRecord> got = controller.listCosts("k", "t1",
                Instant.now().minusSeconds(3600), Instant.now());
        assertThat(got).hasSize(1);
    }

    @Test
    void listCosts_missingFromTo_throws400_GW4301() {
        assertThatThrownBy(() -> controller.listCosts("k", "t1", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400")
                .hasMessageContaining("GW-4301");
    }

    // --- GET /usage/export ---

    @Test
    void exportUsage_csv_returnsRecords() {
        billing.records.add(rec("r1"));
        ResponseEntity<List<UsageRecord>> resp = controller.exportUsage("k", "t1",
                ExportFormat.CSV, Instant.now().minusSeconds(3600), Instant.now());
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).hasSize(1);
    }

    @Test
    void exportUsage_failure_throws500_GW4303() {
        billing.fail = true;
        assertThatThrownBy(() -> controller.exportUsage("k", "t1",
                ExportFormat.CSV, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("GW-4303");
    }

    // --- POST /budgets ---

    @Test
    void createBudget_valid_succeeds_201_andPublishes() {
        var resp = controller.createBudget("k", "t1", new AdminBillingController.BudgetRequest(
                BudgetType.MONEY, new BigDecimal("50"), new BigDecimal("100"), 80, "ALERT"));
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        assertThat(publisher.last).isNotNull();
        assertThat(controller.findBudget("k", "t1")).isPresent();
    }

    @Test
    void createBudget_dailyExceedsMonthly_throws400_GW4302() {
        assertThatThrownBy(() -> controller.createBudget("k", "t1",
                new AdminBillingController.BudgetRequest(
                        BudgetType.MONEY, new BigDecimal("100"), new BigDecimal("50"), 80, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("GW-4302");
    }

    @Test
    void createBudget_invalidThreshold_throws400_GW4306() {
        assertThatThrownBy(() -> controller.createBudget("k", "t1",
                new AdminBillingController.BudgetRequest(
                        BudgetType.MONEY, new BigDecimal("10"), new BigDecimal("100"), 0, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("GW-4306");
    }

    @Test
    void createBudget_autoSuspendRejected_throws400_GW4306() {
        // SUSPEND 必须显式管理员动作，REST 自动配置拒绝（自动策略只到 THROTTLE）
        assertThatThrownBy(() -> controller.createBudget("k", "t1",
                new AdminBillingController.BudgetRequest(
                        BudgetType.MONEY, new BigDecimal("10"), new BigDecimal("100"), 80, "SUSPEND")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("GW-4306")
                .hasMessageContaining("THROTTLE");
    }

    @Test
    void createBudget_invalidActionValue_throws400_GW4306() {
        assertThatThrownBy(() -> controller.createBudget("k", "t1",
                new AdminBillingController.BudgetRequest(
                        BudgetType.MONEY, new BigDecimal("10"), new BigDecimal("100"), 80, "SHUTDOWN")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("GW-4306");
    }

    @Test
    void createBudget_throttleAction_accepted() {
        var resp = controller.createBudget("k", "t1", new AdminBillingController.BudgetRequest(
                BudgetType.TOKEN, new BigDecimal("50000"), new BigDecimal("1000000"), 90, "THROTTLE"));
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        assertThat(resp.getBody().suspendAction()).isEqualTo(Budget.QuotaAction.THROTTLE);
    }

    // --- PUT / DELETE / GET ---

    @Test
    void updateBudget_upserts() {
        controller.createBudget("k", "t1", new AdminBillingController.BudgetRequest(
                BudgetType.MONEY, new BigDecimal("50"), new BigDecimal("100"), 80, null));
        var resp = controller.updateBudget("k", "t1", new AdminBillingController.BudgetRequest(
                BudgetType.MONEY, new BigDecimal("80"), new BigDecimal("200"), 90, null));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(controller.findBudget("k", "t1").orElseThrow().dailyLimit())
                .isEqualByComparingTo("80");
    }

    @Test
    void deleteBudget_removes_andCancelsSuspendCoolingOff() {
        controller.createBudget("k", "t1", new AdminBillingController.BudgetRequest(
                BudgetType.MONEY, new BigDecimal("50"), new BigDecimal("100"), 80, null));
        var resp = controller.deleteBudget("k", "t1");
        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        assertThat(controller.findBudget("k", "t1")).isEmpty();
    }

    @Test
    void findBudget_unknownTenant_returnsEmpty() {
        assertThat(controller.findBudget("k", "nope")).isEmpty();
    }

    @Test
    void tenantIsolation_recordsScopedToTenant() {
        billing.records.add(rec("r1")); // tenant t1
        List<UsageRecord> other = controller.listCosts("k", "t2",
                Instant.now().minusSeconds(3600), Instant.now());
        assertThat(other).isEmpty(); // t2 查不到 t1 的记账
    }

    // --- 测试桩 ---

    static class InMemoryBilling implements com.company.agentgateway.domain.billing.BillingPort {
        final List<UsageRecord> records = new CopyOnWriteArrayList<>();
        boolean fail;

        public void recordUsage(UsageRecord r) { records.add(r); }
        public List<UsageRecord> queryUsage(com.company.agentgateway.domain.billing.UsageQuery q) {
            if (fail) throw new RuntimeException("storage down");
            return records.stream().filter(r -> r.tenant().equals(q.tenant())).toList();
        }
        public BigDecimal queryCost(com.company.agentgateway.domain.billing.UsageQuery q) {
            return queryUsage(q).stream().map(UsageRecord::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        public List<UsageRecord> exportUsage(com.company.agentgateway.domain.billing.UsageQuery q, ExportFormat f) {
            if (fail) throw new RuntimeException("export failed");
            return queryUsage(q);
        }
    }

    static class InMemoryBudgets implements BudgetRepository {
        final java.util.Map<TenantId, Budget> store = new java.util.concurrent.ConcurrentHashMap<>();
        public Optional<Budget> findByTenant(TenantId t) { return Optional.ofNullable(store.get(t)); }
        public void save(Budget b) { store.put(b.tenant(), b); }
        public void delete(TenantId t) { store.remove(t); }
        public boolean markAlertSent(TenantId t) { return false; }
        public void accumulateUsage(TenantId t, BigDecimal a) { }
    }

    static class RecordingPublisher implements RbacChangePublisher {
        volatile RbacChangeEvent last;
        public Flow.Publisher<RbacChangeEvent> publish(RbacChangeEvent event) {
            last = event;
            SubmissionPublisher<RbacChangeEvent> sp = new SubmissionPublisher<>();
            sp.submit(event);
            return sp;
        }
    }
}
