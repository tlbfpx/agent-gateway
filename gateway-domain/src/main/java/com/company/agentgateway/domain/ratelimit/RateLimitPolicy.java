package com.company.agentgateway.domain.ratelimit;

/**
 * 限流策略（spec 2026-09-03 §rate-limit §3.1）。
 *
 * <p>单租户 / 单 API 路径的限流配置：
 * <ul>
 *   <li>capacity 桶容量(令牌数)</li>
 *   <li>refillTokensPerSec 每秒补充速率</li>
 *   <li>key 维度(tenant / apiKey / path)</li>
 * </ul>
 */
public record RateLimitPolicy(
        String key,
        long capacity,
        double refillTokensPerSec) {

    /** 校验参数合法性 */
    public RateLimitPolicy {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key required");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
        }
        if (refillTokensPerSec <= 0) {
            throw new IllegalArgumentException("refillTokensPerSec must be > 0, got " + refillTokensPerSec);
        }
    }

    /** 全局默认：每租户 100 req / s,burst 200 */
    public static RateLimitPolicy defaultTenantPolicy(String tenantId) {
        return new RateLimitPolicy("tenant:" + tenantId, 200, 100);
    }
}