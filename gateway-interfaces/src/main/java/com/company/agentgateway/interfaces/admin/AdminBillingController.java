package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.billing.AlertThreshold;
import com.company.agentgateway.domain.billing.BillingErrorCode;
import com.company.agentgateway.domain.billing.BillingPort;
import com.company.agentgateway.domain.billing.Budget;
import com.company.agentgateway.domain.billing.BudgetRepository;
import com.company.agentgateway.domain.billing.BudgetType;
import com.company.agentgateway.domain.billing.ExportFormat;
import com.company.agentgateway.domain.billing.UsageQuery;
import com.company.agentgateway.domain.billing.UsageRecord;
import com.company.agentgateway.domain.iam.RbacChangeEvent;
import com.company.agentgateway.domain.iam.RbacChangePublisher;
import com.company.agentgateway.domain.shared.TenantId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 计费管理端点（spec §21.6 + D2 GW-QUOTA-008）。
 *
 * <p>端点（X-API-Key 鉴权，与 D1 AdminRolesController 同款模式）：
 * <ul>
 *   <li>GET    /v1/admin/billing/costs                — 用量成本查询（GW-4301 参数非法）</li>
 *   <li>GET    /v1/admin/billing/usage/export?format  — Chargeback CSV 导出（GW-4303）</li>
 *   <li>GET    /v1/admin/billing/budgets              — 查询预算</li>
 *   <li>POST   /v1/admin/billing/budgets              — 创建/更新预算（GW-4302 冲突 / GW-4306 非法）</li>
 *   <li>PUT    /v1/admin/billing/budgets              — 更新预算</li>
 *   <li>DELETE /v1/admin/billing/budgets              — 删除预算（SUSPEND 冷静期撤销入口）</li>
 * </ul>
 *
 * <p>预算变更事件复用 D1 {@link RbacChangePublisher} 通道（design §4.3，零新基础设施）。
 */
@RestController
@RequestMapping("/v1/admin/billing")
public class AdminBillingController {

    private final BillingPort billingPort;
    private final BudgetRepository budgetRepository;
    private final RbacChangePublisher rbacChangePublisher;

    public AdminBillingController(BillingPort billingPort,
                                  BudgetRepository budgetRepository,
                                  RbacChangePublisher rbacChangePublisher) {
        this.billingPort = billingPort;
        this.budgetRepository = budgetRepository;
        this.rbacChangePublisher = rbacChangePublisher;
    }

    /** GET /costs — 用量查询（from/to 必填，缺省 400 GW-4301）。 */
    @GetMapping("/costs")
    public List<UsageRecord> listCosts(@RequestHeader("X-API-Key") String apiKey,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                       @RequestParam(required = false) Instant from,
                                       @RequestParam(required = false) Instant to) {
        if (from == null || to == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    BillingErrorCode.BILLING_QUERY_INVALID + ": from/to required");
        }
        return billingPort.queryUsage(new UsageQuery(resolveTenant(tenantId), from, to, null, null));
    }

    /** GET /costs 总额（from/to 可选）。 */
    @GetMapping("/costs/total")
    public BigDecimal totalCost(@RequestHeader("X-API-Key") String apiKey,
                                @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                @RequestParam(required = false) Instant from,
                                @RequestParam(required = false) Instant to) {
        if (from == null || to == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    BillingErrorCode.BILLING_QUERY_INVALID + ": from/to required");
        }
        return billingPort.queryCost(new UsageQuery(resolveTenant(tenantId), from, to, null, null));
    }

    /** GET /usage/export?format=CSV — Chargeback 导出（GW-4303 失败 500）。 */
    @GetMapping("/usage/export")
    public ResponseEntity<List<UsageRecord>> exportUsage(@RequestHeader("X-API-Key") String apiKey,
                                                         @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                                         @RequestParam ExportFormat format,
                                                         @RequestParam(required = false) Instant from,
                                                         @RequestParam(required = false) Instant to) {
        try {
            List<UsageRecord> records = billingPort.exportUsage(new UsageQuery(
                    resolveTenant(tenantId),
                    from != null ? from : Instant.now().minusSeconds(30L * 24 * 3600),
                    to != null ? to : Instant.now(),
                    null, null), format);
            return ResponseEntity.ok(records);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    BillingErrorCode.BILLING_EXPORT_FAILED + ": " + e.getMessage());
        }
    }

    /** 预算请求体。 */
    public record BudgetRequest(BudgetType type, BigDecimal dailyLimit, BigDecimal monthlyLimit,
                                int alertThresholdPct, String suspendAction,
                                String overLimitAction, String fallbackModel) {

        /** 兼容旧请求体（无超限动作字段）。 */
        public BudgetRequest(BudgetType type, BigDecimal dailyLimit, BigDecimal monthlyLimit,
                             int alertThresholdPct, String suspendAction) {
            this(type, dailyLimit, monthlyLimit, alertThresholdPct, suspendAction, null, null);
        }
    }

    /** GET /budgets — 当前预算。 */
    @GetMapping("/budgets")
    public Optional<Budget> findBudget(@RequestHeader("X-API-Key") String apiKey,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        return budgetRepository.findByTenant(resolveTenant(tenantId));
    }

    /** POST /budgets — 创建预算（GW-4302 日>月冲突；GW-4306 阈值/策略非法）。 */
    @PostMapping("/budgets")
    public ResponseEntity<Budget> createBudget(@RequestHeader("X-API-Key") String apiKey,
                                               @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                               @RequestBody BudgetRequest body) {
        Budget budget = buildBudget(resolveTenant(tenantId), body);
        budgetRepository.save(budget);
        publishBudgetChange(budget.tenant());
        return ResponseEntity.status(HttpStatus.CREATED).body(budget);
    }

    /** PUT /budgets — 更新预算（upsert 语义）。 */
    @PutMapping("/budgets")
    public ResponseEntity<Budget> updateBudget(@RequestHeader("X-API-Key") String apiKey,
                                               @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                               @RequestBody BudgetRequest body) {
        Budget budget = buildBudget(resolveTenant(tenantId), body);
        budgetRepository.save(budget);
        publishBudgetChange(budget.tenant());
        return ResponseEntity.ok(budget);
    }

    /** DELETE /budgets — 删除预算（同时是 SUSPEND 冷静期的管理员撤销入口）。 */
    @DeleteMapping("/budgets")
    public ResponseEntity<Void> deleteBudget(@RequestHeader("X-API-Key") String apiKey,
                                             @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        budgetRepository.delete(resolveTenant(tenantId));
        return ResponseEntity.noContent().build();
    }

    private Budget buildBudget(TenantId tenant, BudgetRequest body) {
        if (body.dailyLimit() != null && body.monthlyLimit() != null
                && body.dailyLimit().compareTo(body.monthlyLimit()) > 0) {
            // GW-4302：日预算 > 月预算冲突
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    BillingErrorCode.BUDGET_CONFIG_CONFLICT + ": dailyLimit > monthlyLimit");
        }
        AlertThreshold threshold;
        try {
            threshold = new AlertThreshold(body.alertThresholdPct());
        } catch (IllegalArgumentException e) {
            // GW-4306：阈值非法（[1,100] 之外）
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    BillingErrorCode.QUOTA_POLICY_INVALID + ": " + e.getMessage());
        }
        Budget.QuotaAction suspendAction = null;
        if (body.suspendAction() != null && !body.suspendAction().isBlank()) {
            try {
                suspendAction = Budget.QuotaAction.valueOf(body.suspendAction());
            } catch (IllegalArgumentException e) {
                // GW-4306：策略不在 {ALERT, THROTTLE, SUSPEND} 白名单
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        BillingErrorCode.QUOTA_POLICY_INVALID + ": invalid suspendAction " + body.suspendAction());
            }
            if (suspendAction == Budget.QuotaAction.SUSPEND) {
                // SUSPEND 必须显式管理员动作 + 5 分钟冷静期（spec §21.4；自动策略只到 THROTTLE）
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        BillingErrorCode.QUOTA_POLICY_INVALID
                                + ": SUSPEND requires explicit admin console action (cooling-off 5min), "
                                + "auto policy caps at THROTTLE");
            }
        }
        Budget.OverLimitAction overLimitAction = Budget.OverLimitAction.BLOCK;
        if (body.overLimitAction() != null && !body.overLimitAction().isBlank()) {
            try {
                overLimitAction = Budget.OverLimitAction.valueOf(body.overLimitAction());
            } catch (IllegalArgumentException e) {
                // GW-4306：超限动作不在 {BLOCK, DOWNGRADE} 白名单
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        BillingErrorCode.QUOTA_POLICY_INVALID + ": invalid overLimitAction "
                                + body.overLimitAction());
            }
        }
        String fallbackModel = body.fallbackModel();
        if (overLimitAction == Budget.OverLimitAction.DOWNGRADE
                && (fallbackModel == null || fallbackModel.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    BillingErrorCode.QUOTA_POLICY_INVALID
                            + ": fallbackModel required when overLimitAction=DOWNGRADE");
        }
        try {
            return new Budget(tenant, null, body.type(),
                    body.dailyLimit() != null ? body.dailyLimit() : BigDecimal.ZERO,
                    body.monthlyLimit() != null ? body.monthlyLimit() : BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    threshold, false, suspendAction, null,
                    overLimitAction,
                    overLimitAction == Budget.OverLimitAction.DOWNGRADE ? fallbackModel : null);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    BillingErrorCode.QUOTA_POLICY_INVALID + ": " + e.getMessage());
        }
    }

    /** 预算变更发布（复用 D1 通道；失败容错不阻断，design §4.3）。 */
    private void publishBudgetChange(TenantId tenant) {
        try {
            rbacChangePublisher.publish(new RbacChangeEvent(
                    RbacChangeEvent.Kind.ROLE_UPSERT, tenant, null, null, "billing-admin", Instant.now()));
        } catch (Exception ignore) {
            // 失败容错：catch + log 语义（不阻断主流程）
        }
    }

    private static TenantId resolveTenant(String tenantId) {
        return new TenantId(tenantId == null || tenantId.isBlank() ? "primary" : tenantId);
    }
}
