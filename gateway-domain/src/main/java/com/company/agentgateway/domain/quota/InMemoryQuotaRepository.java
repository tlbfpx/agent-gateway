package com.company.agentgateway.domain.quota;

import com.company.agentgateway.domain.billing.UsageAtom;
import com.company.agentgateway.domain.shared.TenantId;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * InMemory QuotaPort 实现（spec §16.2 · D2 设计 §2.2 决策点 D-1）。
 *
 * <p>规范要求：分布式一致性由 Redis Lua 脚本实现（spec §8.3 既定模式）。
 * 一期 InMemory 用于本地契约验证，二期 {@code RedisQuotaRepository} 替换。
 *
 * <p>单实例限额 10000 token（与 Contract Test 对齐），二期接 Redis 后通过 DataId 区分。
 */
public class InMemoryQuotaRepository implements QuotaPort {

    private static final long DEFAULT_LIMIT = 10000L;

    private final Map<QuotaKey, AtomicLong> counters = new ConcurrentHashMap<>();

    @Override
    public QuotaDecision check(QuotaKey key, UsageAtom predicted) {
        long used = counters.computeIfAbsent(key, k -> new AtomicLong(0)).get();
        long totalAfter = used + predicted.tokensIn();
        if (totalAfter > DEFAULT_LIMIT) {
            return new QuotaDecision.Rejected(key.dimension().name(), DEFAULT_LIMIT, totalAfter);
        }
        return new QuotaDecision.Allowed(DEFAULT_LIMIT - totalAfter);
    }

    @Override
    public void consume(QuotaKey key, UsageAtom used) {
        counters.computeIfAbsent(key, k -> new AtomicLong(0)).addAndGet(used.tokensIn());
    }

    @Override
    public void reverse(QuotaKey key, UsageAtom used) {
        counters.computeIfAbsent(key, k -> new AtomicLong(0)).addAndGet(-used.tokensIn());
    }

    @Override
    public List<QuotaDecision> snapshot(TenantId tenant) {
        return counters.entrySet().stream()
                .filter(e -> e.getKey().tenant().equals(tenant))
                .map(e -> (QuotaDecision) new QuotaDecision.Allowed(DEFAULT_LIMIT - e.getValue().get()))
                .toList();
    }
}
