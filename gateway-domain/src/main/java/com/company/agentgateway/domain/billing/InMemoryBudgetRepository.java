package com.company.agentgateway.domain.billing;

import com.company.agentgateway.domain.shared.TenantId;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemory BudgetRepository 实现（spec §21.4 · D2 设计 §2.4）。
 */
public class InMemoryBudgetRepository implements BudgetRepository {

    private final Map<TenantId, Budget> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Budget> findByTenant(TenantId tenant) {
        return Optional.ofNullable(store.get(tenant));
    }

    @Override
    public void save(Budget budget) {
        store.put(budget.tenant(), budget);
    }

    @Override
    public void delete(TenantId tenant) {
        store.remove(tenant);
    }

    @Override
    public boolean markAlertSent(TenantId tenant) {
        Budget b = store.get(tenant);
        if (b == null || b.alertSent()) return false;
        store.put(tenant, new Budget(b.tenant(), b.user(), b.type(),
                b.dailyLimit(), b.monthlyLimit(),
                b.currentDailyUsed(), b.currentMonthlyUsed(),
                b.alertThreshold(), true, b.suspendAction(), b.suspendUntil()));
        return true;
    }

    @Override
    public void accumulateUsage(TenantId tenant, BigDecimal amount) {
        Budget b = store.get(tenant);
        if (b == null) return;
        store.put(tenant, new Budget(b.tenant(), b.user(), b.type(),
                b.dailyLimit(), b.monthlyLimit(),
                b.currentDailyUsed().add(amount), b.currentMonthlyUsed().add(amount),
                b.alertThreshold(), b.alertSent(), b.suspendAction(), b.suspendUntil()));
    }
}
