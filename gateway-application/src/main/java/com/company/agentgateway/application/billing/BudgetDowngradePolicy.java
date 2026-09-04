package com.company.agentgateway.application.billing;

import com.company.agentgateway.domain.billing.Budget;
import com.company.agentgateway.domain.billing.BudgetRepository;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;

import java.util.Optional;

/**
 * 超限降级策略（P1 预算告警接线 + 超限降级）。
 *
 * <p>Budget.overLimitAction=DOWNGRADE 时，配额超限（token 日预算 429 点）
 * 由 ChatOrchestrator 降级到 Budget 配置的 fallbackModel 而非直接拒绝；
 * 仅当原模型非 fallback 时降级（否则无降级空间 → 维持 BLOCK 语义）。
 * overLimitAction 缺省 BLOCK（现状 429），行为不变。
 */
public class BudgetDowngradePolicy {

    private final BudgetRepository budgetRepository;

    public BudgetDowngradePolicy(BudgetRepository budgetRepository) {
        this.budgetRepository = budgetRepository;
    }

    /**
     * 查询租户超限降级目标模型。
     *
     * @return 可降级时返回 fallbackModel；否则 empty（维持拒绝）
     */
    public Optional<ModelId> downgradeModelFor(TenantId tenant, ModelId requested) {
        try {
            Optional<Budget> budget = budgetRepository.findByTenant(tenant);
            if (budget.isEmpty()) return Optional.empty();
            Budget b = budget.get();
            if (b.overLimitAction() != Budget.OverLimitAction.DOWNGRADE) return Optional.empty();
            String fallback = b.fallbackModel();
            if (fallback == null || fallback.isBlank()) return Optional.empty();
            if (fallback.equals(requested.value())) return Optional.empty(); // 原模型即 fallback：无降级空间
            return Optional.of(new ModelId(fallback));
        } catch (Exception e) {
            return Optional.empty(); // 查询失败：保守按 BLOCK（不放大故障）
        }
    }
}
