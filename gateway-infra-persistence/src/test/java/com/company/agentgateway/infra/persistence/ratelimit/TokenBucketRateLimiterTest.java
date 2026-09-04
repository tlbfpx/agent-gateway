package com.company.agentgateway.infra.persistence.ratelimit;

import com.company.agentgateway.domain.ratelimit.RateLimitDecision;
import com.company.agentgateway.domain.ratelimit.RateLimitPolicy;
import com.company.agentgateway.domain.ratelimit.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketRateLimiterTest {

    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new TokenBucketRateLimiter();
    }

    @Test
    void firstCall_alwaysAllowed() {
        RateLimitPolicy policy = RateLimitPolicy.defaultTenantPolicy("t1");
        RateLimitDecision d = limiter.tryAcquire(policy);
        assertTrue(d.allowed());
        assertEquals(200, d.limit());
    }

    @Test
    void exhaustingBucket_eventuallyBlocks() {
        // 容量 5,补充 1/秒:前 5 次 OK,第 6 次 block
        RateLimitPolicy policy = new RateLimitPolicy("t-burst", 5, 1);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(policy).allowed(), "call " + i);
        }
        RateLimitDecision blocked = limiter.tryAcquire(policy);
        assertFalse(blocked.allowed());
        assertTrue(blocked.retryAfterMs() > 0);
    }

    @Test
    void independentPolicies_isolatedBuckets() {
        RateLimitPolicy a = new RateLimitPolicy("a", 2, 1);
        RateLimitPolicy b = new RateLimitPolicy("b", 2, 1);
        // 耗尽 a
        assertTrue(limiter.tryAcquire(a).allowed());
        assertTrue(limiter.tryAcquire(a).allowed());
        assertFalse(limiter.tryAcquire(a).allowed());
        // b 不受影响
        assertTrue(limiter.tryAcquire(b).allowed());
        assertTrue(limiter.tryAcquire(b).allowed());
    }

    @Test
    void refillAllowsMore() throws InterruptedException {
        // 容量 2,补充 100/秒:2 次 OK,等 50ms 再 OK
        RateLimitPolicy policy = new RateLimitPolicy("t-rapid", 2, 100);
        assertTrue(limiter.tryAcquire(policy).allowed());
        assertTrue(limiter.tryAcquire(policy).allowed());
        Thread.sleep(50);
        assertTrue(limiter.tryAcquire(policy).allowed());
    }

    @Test
    void snapshotReturnsStateAfterAcquire() {
        RateLimitPolicy policy = new RateLimitPolicy("snap", 10, 1);
        // 先 acquire 创建 bucket
        limiter.tryAcquire(policy);
        limiter.tryAcquire(policy);
        RateLimiter.Snapshot s = limiter.snapshot(policy.key());
        assertEquals(10L, s.capacity());
        assertTrue(s.currentTokens() < 10);
    }

    @Test
    void policyRejectsInvalidParams() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimitPolicy(null, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> new RateLimitPolicy("k", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RateLimitPolicy("k", 10, 0));
    }

    private static <T extends Throwable> T assertThrows(Class<T> expected, Runnable r) {
        try {
            r.run();
            throw new AssertionError("expected " + expected);
        } catch (Throwable t) {
            if (expected.isInstance(t)) return expected.cast(t);
            throw new AssertionError("expected " + expected + " but got " + t, t);
        }
    }
}