package com.company.agentgateway.application.billing;

import com.company.agentgateway.domain.billing.Budget;
import com.company.agentgateway.domain.billing.BudgetRepository;
import com.company.agentgateway.domain.iam.RbacChangeEvent;
import com.company.agentgateway.domain.iam.RbacChangePublisher;
import com.company.agentgateway.domain.observability.AlertStore;
import com.company.agentgateway.domain.observability.GatewayEvents;
import com.company.agentgateway.domain.shared.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 预算守卫（spec §21.4 + D2 GW-QUOTA-007）。
 *
 * <p>流程：BillingEngine 落账后 → BudgetGuard.onUsageAccumulated →
 * 累加 + 阈值校验 + 告警触发（复用 D1 {@link RbacChangePublisher}）。
 *
 * <p>P1 告警接线：阈值（默认 80%）与 100% 两级告警写入 {@link AlertStore}
 * （PgAlertStore / 内存实现），并通过 {@link GatewayEvents} 触发 Webhook 推送
 * （interfaces 层 WebhookEventBridge → WebhookDispatcher）。两级告警均按
 * dedupKey 去重，避免每笔用量重复告警。
 *
 * <p>失败容错（spec §GW-QUOTA-007）：告警链路异常 catch + log warn，不阻断。
 */
public class BudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(BudgetGuard.class);

    static final int CRITICAL_PCT = 100;
    static final String SEVERITY_WARNING = "warning";
    static final String SEVERITY_CRITICAL = "critical";

    private final BudgetRepository budgetRepository;
    private final RbacChangePublisher rbacChangePublisher;
    /** 可选：告警记录存储（缺失时仅保留原有 publisher 通道）。 */
    private final AlertStore alertStore;
    /** 可选：事件端口（Webhook 桥接在 interfaces 层）。 */
    private final GatewayEvents events;

    public BudgetGuard(BudgetRepository budgetRepository, RbacChangePublisher rbacChangePublisher) {
        this(budgetRepository, rbacChangePublisher, null, GatewayEvents.NOOP);
    }

    public BudgetGuard(BudgetRepository budgetRepository, RbacChangePublisher rbacChangePublisher,
                       AlertStore alertStore, GatewayEvents events) {
        this.budgetRepository = budgetRepository;
        this.rbacChangePublisher = rbacChangePublisher;
        this.alertStore = alertStore;
        this.events = events != null ? events : GatewayEvents.NOOP;
    }

    /**
     * 累加用量 + 检查预算阈值；超阈值且未发过则触发告警（幂等）。
     */
    public void onUsageAccumulated(TenantId tenant, BigDecimal amount) {
        try {
            var maybeBudget = budgetRepository.findByTenant(tenant);
            if (maybeBudget.isEmpty()) return; // 无预算 = 无监控
            // 1. 累加
            budgetRepository.accumulateUsage(tenant, amount);
            // 2. 重新读取（accumulateUsage 已更新）
            Budget updated = budgetRepository.findByTenant(tenant).orElseThrow();
            // 3. 阈值校验（按 dailyLimit 维度）
            BigDecimal limit = updated.dailyLimit();
            if (limit == null || limit.signum() == 0) return; // 未设上限
            BigDecimal used = updated.currentDailyUsed();
            int thresholdPct = updated.alertThreshold().percent();
            BigDecimal thresholdAmount = limit.multiply(BigDecimal.valueOf(thresholdPct))
                    .divide(BigDecimal.valueOf(100));
            // 4. 超阈值且 !alertSent → 触发告警（幂等：markAlertSent 返回 false 表示已发过）
            if (used.compareTo(thresholdAmount) > 0 && !updated.alertSent()) {
                publishAlert(tenant, updated, used, limit, thresholdPct);
                budgetRepository.markAlertSent(tenant);
            }
            // 5. P1 两级告警（AlertStore + Webhook，dedupKey 去重）
            if (alertStore != null) {
                recordLevelAlerts(tenant, used, limit, thresholdPct);
            }
        } catch (Exception e) {
            log.warn("BudgetGuard failed (swallowed): tenant={} amount={} msg={}",
                    tenant.value(), amount, e.getMessage());
        }
    }

    /** 两级告警：thresholdPct（warning）与 100%（critical）；dedupKey 级去重。 */
    private void recordLevelAlerts(TenantId tenant, BigDecimal used, BigDecimal limit, int thresholdPct) {
        int usedPct = used.multiply(BigDecimal.valueOf(100))
                .divide(limit, 0, RoundingMode.DOWN).intValueExact();
        if (usedPct >= thresholdPct) {
            fireLevelAlert(tenant, used, limit, thresholdPct, SEVERITY_WARNING);
        }
        if (usedPct >= CRITICAL_PCT) {
            fireLevelAlert(tenant, used, limit, CRITICAL_PCT, SEVERITY_CRITICAL);
        }
    }

    /** 单级告警：已存在同 dedupKey 的 firing 记录则跳过（去重，避免每笔用量重复告警）。 */
    private void fireLevelAlert(TenantId tenant, BigDecimal used, BigDecimal limit,
                                int levelPct, String severity) {
        String dedupKey = "budget:" + tenant.value() + ":" + levelPct;
        Optional<AlertStore.AlertRecord> existing = alertStore.findLatestByDedupKey(dedupKey);
        if (existing.isPresent() && "firing".equals(existing.get().state())) {
            return; // 该级已告警过：去重
        }
        Instant now = Instant.now();
        AlertStore.AlertRecord record = alertStore.insertFiring(new AlertStore.AlertRecord(
                null, "budget-guard", severity, "firing", dedupKey,
                Map.of("tenant", tenant.value(),
                        "level", String.valueOf(levelPct),
                        "used", used.toPlainString(),
                        "limit", limit.toPlainString()),
                now, now, 1,
                used.multiply(BigDecimal.valueOf(100)).divide(limit, 2, RoundingMode.HALF_UP).doubleValue(),
                (double) levelPct, null, null, null));
        // Webhook 推送（经 GatewayEvents → WebhookEventBridge → WebhookDispatcher）
        try {
            events.publish("budget.alert", Map.of(
                    "tenant", tenant.value(),
                    "severity", severity,
                    "levelPct", levelPct,
                    "used", used.toPlainString(),
                    "limit", limit.toPlainString(),
                    "alertId", String.valueOf(record.id())));
        } catch (Exception e) {
            log.warn("BudgetGuard webhook push failed: {}", e.getMessage());
        }
        log.info("BudgetGuard level alert tenant={} levelPct={} severity={} used={} limit={}",
                tenant.value(), levelPct, severity, used, limit);
    }

    private void publishAlert(TenantId tenant, Budget b, BigDecimal used, BigDecimal limit, int thresholdPct) {
        try {
            // 复用 D1 RbacChangePublisher 通道（一期 BUDGET_EXCEEDED 借用 ROLE_UPSERT；
            // actor 字段携带告警语义，二期添加独立 BUDGET_EXCEEDED enum）
            RbacChangeEvent event = new RbacChangeEvent(
                    RbacChangeEvent.Kind.ROLE_UPSERT, tenant, null, null,
                    "BUDGET_EXCEEDED:tenant=" + tenant.value()
                            + ";used=" + used.toPlainString()
                            + ";limit=" + limit.toPlainString()
                            + ";thresholdPct=" + thresholdPct,
                    Instant.now());
            rbacChangePublisher.publish(event);
            log.info("BudgetGuard alerted tenant={} used={} limit={} thresholdPct={}",
                    tenant.value(), used, limit, thresholdPct);
        } catch (Exception e) {
            log.warn("BudgetGuard publish failed: {}", e.getMessage());
        }
    }
}
