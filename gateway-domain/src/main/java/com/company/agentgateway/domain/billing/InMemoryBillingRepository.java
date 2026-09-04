package com.company.agentgateway.domain.billing;

import com.company.agentgateway.domain.shared.TenantId;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * InMemory BillingPort 实现（spec §GW-QUOTA-004 · D2 设计 §2.4）。
 *
 * <p>结构：ConcurrentHashMap&lt;TenantId, CopyOnWriteArrayList&lt;UsageRecord&gt;&gt;。
 * 二期 JPA 通过 @Primary 覆盖。
 */
public class InMemoryBillingRepository implements BillingPort {

    private final Map<TenantId, CopyOnWriteArrayList<UsageRecord>> store = new ConcurrentHashMap<>();

    @Override
    public void recordUsage(UsageRecord record) {
        store.computeIfAbsent(record.tenant(), k -> new CopyOnWriteArrayList<>()).add(record);
    }

    @Override
    public List<UsageRecord> queryUsage(UsageQuery query) {
        return store.getOrDefault(query.tenant(), new CopyOnWriteArrayList<>()).stream()
                .filter(r -> query.model() == null || r.model().equals(query.model()))
                .filter(r -> query.agentName() == null || query.agentName().equals(r.agentName()))
                .filter(r -> query.from() == null || !r.timestamp().isBefore(query.from()))
                .filter(r -> query.to() == null || !r.timestamp().isAfter(query.to()))
                .toList();
    }

    @Override
    public BigDecimal queryCost(UsageQuery query) {
        return queryUsage(query).stream()
                .map(UsageRecord::cost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public List<UsageRecord> exportUsage(UsageQuery query, ExportFormat format) {
        // 一期：与 queryUsage 相同；二期按 format 转 CSV 字符串
        return queryUsage(query);
    }
}
