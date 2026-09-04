package com.company.agentgateway.domain.ratelimit;

/**
 * 限流器端口（spec 2026-09-03 §rate-limit §4）。
 *
 * <p>实现：
 * <ul>
 *   <li>P0：{@code TokenBucketRateLimiter}（内存 token bucket;R19 + swap Pg）</li>
 * </ul>
 */
public interface RateLimiter {

    /**
     * 尝试消费一个令牌。
     *
     * @param policy 限流策略
     * @return 决策：allow 或 block(retryAfterMs)
     */
    RateLimitDecision tryAcquire(RateLimitPolicy policy);

    /**
     * 当前桶状态(用于 metrics)。allow 时 remaining 可能 < capacity。
     */
    Snapshot snapshot(String key);

    record Snapshot(long capacity, double currentTokens, long lastRefillEpochMs) {}
}
