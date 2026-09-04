package com.company.agentgateway.infra.security;

import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.RateLimiter;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 令牌桶限流（InMemory 一期实现，spec §8.3）。
 *
 * <p>四个 QPS 维度（租户/用户/Key/Agent 并发）+ 租户 token 日预算。
 * 桶按「窗口起始秒 + 计数」实现固定窗口（简单可预测；滑动窗口二期换 Redis）。
 * 所有阈值可配，0 = 不限。
 */
public class InMemoryRateLimiter implements RateLimiter {

    /** 每秒窗口计数：key → [windowSec, count] */
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    /** Agent 并发计数 */
    private final Map<String, AtomicInteger> agentInflight = new ConcurrentHashMap<>();
    /** 租户 token 日累计：tenant → [dayEpoch, tokens] */
    private final Map<String, DayCounter> dayTokens = new ConcurrentHashMap<>();

    private final long tenantQps;
    private final long userQps;
    private final long apiKeyQps;
    private final int maxAgentConcurrency;
    private final long tenantDailyTokenBudget;

    public InMemoryRateLimiter(long tenantQps, long userQps, long apiKeyQps,
                               int maxAgentConcurrency, long tenantDailyTokenBudget) {
        this.tenantQps = tenantQps;
        this.userQps = userQps;
        this.apiKeyQps = apiKeyQps;
        this.maxAgentConcurrency = maxAgentConcurrency;
        this.tenantDailyTokenBudget = tenantDailyTokenBudget;
    }

    @Override
    public String tryAcquire(AuthPrincipal principal, String apiKey, String agentName) {
        long now = System.currentTimeMillis() / 1000;
        if (tenantQps > 0 && !hit("t:" + principal.tenant().value(), now, tenantQps)) {
            return "tenant-qps";
        }
        if (userQps > 0 && !hit("u:" + principal.user().value(), now, userQps)) {
            return "user-qps";
        }
        if (apiKey != null && !apiKey.isBlank() && apiKeyQps > 0
                && !hit("k:" + apiKey, now, apiKeyQps)) {
            return "api-key-qps";
        }
        if (agentName != null && maxAgentConcurrency > 0) {
            AtomicInteger inflight = agentInflight.computeIfAbsent(agentName, k -> new AtomicInteger());
            if (inflight.incrementAndGet() > maxAgentConcurrency) {
                inflight.decrementAndGet();
                return "agent-concurrency";
            }
        }
        return null; // 允许
    }

    @Override
    public boolean tryAcquireTokens(TenantId tenant, long tokens) {
        if (tenantDailyTokenBudget <= 0) return true; // 不限
        long day = System.currentTimeMillis() / 86_400_000L;
        DayCounter c = dayTokens.computeIfAbsent(tenant.value(), k -> new DayCounter());
        synchronized (c) {
            if (c.day != day) { c.day = day; c.tokens = 0; } // 跨天重置
            if (c.tokens + tokens > tenantDailyTokenBudget) return false;
            c.tokens += tokens;
            return true;
        }
    }

    @Override
    public void release(String agentName) {
        if (agentName == null) return;
        AtomicInteger inflight = agentInflight.get(agentName);
        if (inflight != null) inflight.decrementAndGet();
    }

    /** 固定窗口命中（true=允许）。 */
    private boolean hit(String key, long nowSec, long limit) {
        Window w = windows.computeIfAbsent(key, k -> new Window());
        synchronized (w) {
            if (w.sec != nowSec) { w.sec = nowSec; w.count = 0; }
            w.count++;
            return w.count <= limit;
        }
    }

    private static final class Window {
        long sec;
        long count;
    }

    private static final class DayCounter {
        long day;
        long tokens;
    }
}
