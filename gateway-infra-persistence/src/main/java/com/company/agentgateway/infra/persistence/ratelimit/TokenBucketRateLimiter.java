package com.company.agentgateway.infra.persistence.ratelimit;

import com.company.agentgateway.domain.ratelimit.RateLimitDecision;
import com.company.agentgateway.domain.ratelimit.RateLimitPolicy;
import com.company.agentgateway.domain.ratelimit.RateLimiter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Token bucket 限流器（spec 2026-09-03 §rate-limit §4.1）。
 *
 * <p>经典 token bucket：每桶 capacity 个令牌,按 {@code refillTokensPerSec} 速率补充;
 * tryAcquire 原子减少令牌,不足返 block + 计算 retryAfter。
 *
 * <p>线程安全：每个 bucket 一个 Bucket 对象 + synchronized。
 * P0 内存;R19+1 接 Pg(用 {@code ratelimit_bucket} 表,pg_advisory_lock 同步)。
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public RateLimitDecision tryAcquire(RateLimitPolicy policy) {
        Bucket b = buckets.computeIfAbsent(policy.key(), k -> new Bucket(policy.capacity()));
        synchronized (b) {
            refillIfNeeded(b, policy);
            if (b.tokens >= 1.0) {
                b.tokens -= 1.0;
                long resetSec = b.lastRefillEpochMs / 1000 + (long) Math.ceil(
                        (policy.capacity() - b.tokens) / policy.refillTokensPerSec());
                return RateLimitDecision.allow(policy.capacity(), (long) b.tokens, resetSec);
            }
            // 缺 N 个令牌,需等 N/refillPerSec 秒
            double deficit = 1.0 - b.tokens;
            long retryMs = (long) Math.ceil(deficit / policy.refillTokensPerSec() * 1000);
            long resetSec = b.lastRefillEpochMs / 1000 + (long) Math.ceil(
                    policy.capacity() / policy.refillTokensPerSec());
            return RateLimitDecision.block(policy.capacity(), retryMs, resetSec);
        }
    }

    @Override
    public Snapshot snapshot(String key) {
        // Note: snapshot() 仅用于 metrics;无法得知 policy.capacity(不带过来),
        // 所以这里只返回当前 token 数,capacity 字段填 0(调用方应已知 policy)。
        Bucket b = buckets.get(key);
        if (b == null) return new Snapshot(0, 0, 0);
        synchronized (b) {
            return new Snapshot((long) b.capacity, b.tokens, b.lastRefillEpochMs);
        }
    }

    private static void refillIfNeeded(Bucket b, RateLimitPolicy policy) {
        long now = System.currentTimeMillis();
        long delta = now - b.lastRefillEpochMs;
        if (delta <= 0) return;
        double add = delta / 1000.0 * policy.refillTokensPerSec();
        b.tokens = Math.min(policy.capacity(), b.tokens + add);
        b.lastRefillEpochMs = now;
    }

    private static class Bucket {
        double tokens;
        final double capacity;
        long lastRefillEpochMs;

        Bucket(double capacity) {
            this.capacity = capacity;
            this.tokens = capacity;
            this.lastRefillEpochMs = System.currentTimeMillis();
        }
    }
}