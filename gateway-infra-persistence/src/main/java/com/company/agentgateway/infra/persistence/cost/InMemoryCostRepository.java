package com.company.agentgateway.infra.persistence.cost;

import com.company.agentgateway.domain.billing.CostRepository;
import com.company.agentgateway.domain.shared.TenantId;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * CostRepository 内存实现（默认）。用量记录存 List，聚合查询实时计算。
 * 预算按 tenant 存（无预算=无限额）。
 */
public class InMemoryCostRepository implements CostRepository {

    private final List<UsageRecord> records = new CopyOnWriteArrayList<>();
    private final Map<TenantId, Budget> budgets = new ConcurrentHashMap<>();

    @Override
    public void recordUsage(UsageRecord record) {
        records.add(record);
    }

    @Override
    public List<CostSummary> queryCosts(TenantId tenant, LocalDate from, LocalDate to) {
        // 按 (model, date) 分组聚合
        Map<String, CostSummary> aggregated = new ConcurrentHashMap<>();
        for (UsageRecord r : records) {
            if (!r.tenant().equals(tenant)) continue;
            LocalDate d = r.timestamp().atZone(ZoneOffset.UTC).toLocalDate();
            if (d.isBefore(from) || d.isAfter(to)) continue;
            String key = r.model().value() + "|" + d;
            aggregated.merge(key,
                    new CostSummary(tenant, r.model(), d, r.tokensIn(), r.tokensOut(), r.cost()),
                    (a, b) -> new CostSummary(tenant, a.model(), a.date(),
                            a.totalTokensIn() + b.totalTokensIn(),
                            a.totalTokensOut() + b.totalTokensOut(),
                            a.totalCost().add(b.totalCost())));
        }
        return new ArrayList<>(aggregated.values());
    }

    @Override
    public Budget getBudget(TenantId tenant) {
        return budgets.getOrDefault(tenant, new Budget(tenant, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO, false));
    }

    @Override
    public void setBudget(Budget budget) {
        budgets.put(budget.tenant(), budget);
    }
}
