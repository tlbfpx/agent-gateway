package com.company.agentgateway.domain.ratelimit;

/**
 * 限流判定结果（spec 2026-09-03 §rate-limit §3）。
 *
 * <p>{@link #allowed} true → 请求通过;false → 429。
 * {@link #retryAfterMs} 仅 blocked 时有意义(RFC 7231 Retry-After 字段)。
 * {@link #remaining} 用于 X-RateLimit-Remaining header。
 */
public record RateLimitDecision(
        boolean allowed,
        long limit,
        long remaining,
        long retryAfterMs,
        long resetAtEpochSec) {

    public static RateLimitDecision allow(long limit, long remaining, long resetAtEpochSec) {
        return new RateLimitDecision(true, limit, remaining, 0, resetAtEpochSec);
    }

    public static RateLimitDecision block(long limit, long retryAfterMs, long resetAtEpochSec) {
        return new RateLimitDecision(false, limit, 0, retryAfterMs, resetAtEpochSec);
    }
}