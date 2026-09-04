package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;

/**
 * 出站端口：限流（spec §8.3 五维度）。infra 实现（InMemory 一期 / Redis 二期）。
 *
 * <p>五维度：租户 QPS / 用户 QPS / API Key QPS / Agent 并发 / token 日预算。
 * 前四维由 {@link #tryAcquire} 在编排入口统一校验；token 预算由
 * {@link #tryAcquireTokens} 在用量产出时扣减（spec §21.4 单一数据源）。
 */
public interface RateLimiter {

    /**
     * 入口校验（QPS 四维度 + Agent 并发可选）。
     * @return 允许返回 null；拒绝返回原因（如 "tenant-qps"/"user-qps"/"agent-concurrency"），
     *         供 429 响应标注超限维度。
     */
    String tryAcquire(AuthPrincipal principal, String apiKey, String agentName);

    /** token 预算扣减（成功返回 true；预算不足 false → 429）。 */
    boolean tryAcquireTokens(TenantId tenant, long tokens);

    /** 释放一个 Agent 并发槽（调用结束时）。 */
    void release(String agentName);

    RateLimiter NOOP = new RateLimiter() {
        @Override public String tryAcquire(AuthPrincipal p, String k, String a) { return null; }
        @Override public boolean tryAcquireTokens(TenantId t, long tokens) { return true; }
        @Override public void release(String a) {}
    };
}
