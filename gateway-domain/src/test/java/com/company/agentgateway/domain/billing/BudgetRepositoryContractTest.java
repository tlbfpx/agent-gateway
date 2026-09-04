package com.company.agentgateway.domain.billing;

import com.company.agentgateway.domain.shared.TenantId;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class BudgetRepositoryContractTest {

    private final BudgetRepository repo = new InMemoryBudgetRepository();
    private final TenantId t = new TenantId("t1");

    private Budget budget(TenantId tenant) {
        return new Budget(tenant, null, BudgetType.MONEY,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
                new AlertThreshold(80), false, null, null);
    }

    @Test
    void save_thenFindByTenant_returnsSameBudget() {
        repo.save(budget(t));
        assertThat(repo.findByTenant(t)).contains(budget(t));
    }

    @Test
    void tenantIsolation_diffTenant_notVisible() {
        repo.save(budget(t));
        assertThat(repo.findByTenant(new TenantId("t2"))).isEmpty();
    }

    @Test
    void markAlertSent_idempotent_returnsFalseOnSecondCall() {
        repo.save(budget(t));
        assertThat(repo.markAlertSent(t)).isTrue();
        assertThat(repo.markAlertSent(t)).isFalse();
        assertThat(repo.findByTenant(t).orElseThrow().alertSent()).isTrue();
    }

    @Test
    void accumulateUsage_sumsUp() {
        repo.save(budget(t));
        repo.accumulateUsage(t, new BigDecimal("3.50"));
        repo.accumulateUsage(t, new BigDecimal("2.00"));
        Budget b = repo.findByTenant(t).orElseThrow();
        assertThat(b.currentDailyUsed()).isEqualByComparingTo("5.50");
        assertThat(b.currentMonthlyUsed()).isEqualByComparingTo("5.50");
    }

    @Test
    void delete_removesBudget() {
        repo.save(budget(t));
        repo.delete(t);
        assertThat(repo.findByTenant(t)).isEmpty();
    }
}
