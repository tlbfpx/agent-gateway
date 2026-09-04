package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.application.billing.BillingEngine;
import com.company.agentgateway.application.billing.BudgetGuard;
import com.company.agentgateway.domain.billing.BillingPort;
import com.company.agentgateway.domain.billing.Budget;
import com.company.agentgateway.domain.billing.BudgetRepository;
import com.company.agentgateway.domain.billing.BudgetType;
import com.company.agentgateway.domain.billing.ExportFormat;
import com.company.agentgateway.domain.billing.InMemoryBillingRepository;
import com.company.agentgateway.domain.billing.InMemoryBudgetRepository;
import com.company.agentgateway.domain.billing.UsageQuery;
import com.company.agentgateway.domain.billing.UsageRecord;
import com.company.agentgateway.domain.iam.RbacChangeEvent;
import com.company.agentgateway.domain.iam.RbacChangePublisher;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * BillingE2ETest — D2 主流程端到端（spec §21.4/21.5/21.6 + GW-QUOTA-007/008）。
 *
 * <p>链路：AdminBillingController 创建预算 → BillingEngine 落账（单价快照）→
 * BudgetGuard 异步告警触发（复用 RbacChangePublisher）→ 幂等不重发 →
 * 删除预算恢复无监控。跨 Controller + Domain Service + InMemory 仓储的真实组件拼装。
 */
class BillingEndToEndTest {

    private final TenantId t = new TenantId("primary");

    private BillingPort billingPort;
    private BudgetRepository budgetRepo;
    private RecordingPublisher publisher;
    private BudgetGuard guard;
    private BillingEngine engine;
    private AdminBillingController controller;

    @BeforeEach
    void setUp() {
        billingPort = new InMemoryBillingRepository();
        budgetRepo = new InMemoryBudgetRepository();
        publisher = new RecordingPublisher();
        guard = new BudgetGuard(budgetRepo, publisher);
        // BillingEngine：单价表 m1 = in 0.001 / out 0.002（per token）+ 落账后触发 BudgetGuard
        engine = new BillingEngine(billingPort, model -> new ModelId.Price(
                new BigDecimal("0.001"), new BigDecimal("0.002")), guard);
        controller = new AdminBillingController(billingPort, budgetRepo, publisher);
    }

    @Test
    void fullLifecycle_createBudgetRecordUsageTriggerAlertCancelRestore() {
        // 1. 创建预算（日 50 / 月 100 / 阈值 80% → 触发线 40）
        var resp = controller.createBudget("k", "primary",
                new AdminBillingController.BudgetRequest(
                        BudgetType.MONEY,
                        new BigDecimal("50"), new BigDecimal("100"), 80, "ALERT"));
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        assertThat(publisher.last.get()).isNotNull(); // 预算变更事件已发布

        // 2. LLM 调用完成 → BillingEngine 落账（单价快照）：1000 in + 5000 out
        //    = 1000×0.001 + 5000×0.002 = 1 + 10 = 11 元
        engine.recordUsage(t, new UserId("u1"), new ModelId("m1"), "agent", 1000, 5000);
        List<UsageRecord> costs = controller.listCosts("k", "primary",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        assertThat(costs).hasSize(1);
        assertThat(costs.get(0).cost()).isEqualByComparingTo("11");
        assertThat(costs.get(0).unitPriceIn()).isEqualByComparingTo("0.001"); // 单价快照可复现

        // 3. BillingGuard 累加 35 → 11+35=46 > 40（80%）→ 告警触发
        guard.onUsageAccumulated(t, new BigDecimal("35"));
        assertThat(budgetRepo.findByTenant(t).orElseThrow().alertSent()).isTrue();
        RbacChangeEvent alertEvent = publisher.last.get();
        assertThat(alertEvent).isNotNull();

        // 4. 二次累加：alertSent=true → 幂等不重发
        publisher.last.set(null);
        guard.onUsageAccumulated(t, BigDecimal.ONE);
        assertThat(publisher.last.get()).isNull();

        // 5. 删除预算（SUSPEND 冷静期撤销入口）→ 恢复无监控，再累加不抛
        assertThat(controller.deleteBudget("k", "primary").getStatusCode().value()).isEqualTo(204);
        assertThat(controller.findBudget("k", "primary")).isEmpty();
        assertThatCode(() -> guard.onUsageAccumulated(t, BigDecimal.ONE))
                .doesNotThrowAnyException();
    }

    @Test
    void chargebackExport_csvFormat_returnsAllRecords() {
        engine.recordUsage(t, new UserId("u1"), new ModelId("m1"), "agent", 100, 200);
        engine.recordUsage(t, new UserId("u2"), new ModelId("m1"), "agent2", 300, 400);
        var resp = controller.exportUsage("k", "primary", ExportFormat.CSV,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).hasSize(2);
        // 总成本 = (100×0.001 + 200×0.002) + (300×0.001 + 400×0.002) = 0.5 + 1.1 = 1.6
        assertThat(billingPort.queryCost(new UsageQuery(t, null, null, null, null)))
                .isEqualByComparingTo("1.6");
    }

    @Test
    void tenantIsolation_otherTenantSeesNothing() {
        engine.recordUsage(t, new UserId("u1"), new ModelId("m1"), "agent", 100, 200);
        List<UsageRecord> other = controller.listCosts("k", "tenant-x",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        assertThat(other).isEmpty();
    }

    static class RecordingPublisher implements RbacChangePublisher {
        final AtomicReference<RbacChangeEvent> last = new AtomicReference<>();
        public Flow.Publisher<RbacChangeEvent> publish(RbacChangeEvent event) {
            last.set(event);
            SubmissionPublisher<RbacChangeEvent> sp = new SubmissionPublisher<>();
            sp.submit(event);
            return sp;
        }
    }
}
