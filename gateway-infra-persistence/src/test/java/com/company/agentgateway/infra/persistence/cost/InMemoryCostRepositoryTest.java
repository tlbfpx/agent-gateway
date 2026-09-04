package com.company.agentgateway.infra.persistence.cost;

import com.company.agentgateway.domain.billing.CostRepository;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCostRepositoryTest {

    private static final TenantId T = new TenantId("t1");

    private final InMemoryCostRepository repo = new InMemoryCostRepository();

    private CostRepository.UsageRecord usage(String model, long in, long out, BigDecimal cost) {
        return new CostRepository.UsageRecord(UUID.randomUUID().toString(), T, new UserId("u1"),
                new ModelId(model), "agent", Instant.now(), in, out, cost);
    }

    @Test
    void recordUsage_记录可查询() {
        repo.recordUsage(usage("qwen", 100, 50, new BigDecimal("0.05")));
        var costs = repo.queryCosts(T, LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        assertThat(costs).hasSize(1);
        assertThat(costs.get(0).totalTokensIn()).isEqualTo(100);
        assertThat(costs.get(0).totalCost()).isEqualByComparingTo(new BigDecimal("0.05"));
    }

    @Test
    void queryCosts_同模型同日聚合() {
        repo.recordUsage(usage("qwen", 100, 50, new BigDecimal("0.05")));
        repo.recordUsage(usage("qwen", 200, 100, new BigDecimal("0.10")));
        var costs = repo.queryCosts(T, LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        assertThat(costs).hasSize(1);
        assertThat(costs.get(0).totalTokensIn()).isEqualTo(300);
        assertThat(costs.get(0).totalCost()).isEqualByComparingTo(new BigDecimal("0.15"));
    }

    @Test
    void queryCosts_不同模型分开聚合() {
        repo.recordUsage(usage("qwen", 100, 50, new BigDecimal("0.05")));
        repo.recordUsage(usage("glm", 200, 100, new BigDecimal("0.10")));
        var costs = repo.queryCosts(T, LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        assertThat(costs).hasSize(2);
    }

    @Test
    void 预算_默认无预算_设置后可查() {
        var defaultBudget = repo.getBudget(T);
        assertThat(defaultBudget.dailyTokenLimit()).isZero();

        repo.setBudget(new CostRepository.Budget(T, 10000, 100000,
                new BigDecimal("10"), new BigDecimal("100"), 0, BigDecimal.ZERO, false));
        var budget = repo.getBudget(T);
        assertThat(budget.dailyTokenLimit()).isEqualTo(10000);
        assertThat(budget.dailyCostLimit()).isEqualByComparingTo(new BigDecimal("10"));
    }

    @Test
    void 不同租户隔离() {
        repo.recordUsage(usage("qwen", 100, 50, new BigDecimal("0.05")));
        var otherCosts = repo.queryCosts(new TenantId("t2"), LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        assertThat(otherCosts).isEmpty();
    }
}
