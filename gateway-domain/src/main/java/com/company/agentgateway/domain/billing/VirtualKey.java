package com.company.agentgateway.domain.billing;

import com.company.agentgateway.domain.shared.TenantId;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Virtual Key（按用户/团队预付费的额度凭证，spec §21.7）。
 *
 * <p>与 {@link UsageRecord} 互补：UsageRecord 是按 LLM 调用落账的「负债」，
 * VirtualKey 是预先充值的「资产」。Stripe 回调入账 → balanceCny 累加；
 * 每次 LLM 调用由 BudgetGuard 扣减（未来接入）。
 *
 * <p>字段：
 * <ul>
 *   <li>monthlyQuotaCny — 月度额度上限（NULL/0 = 不限）</li>
 *   <li>balanceCny — 实时余额（prepaid）</li>
 *   <li>status — ACTIVE 可用 / REVOKED 已吊销</li>
 * </ul>
 */
public record VirtualKey(
        String vkId,
        String owner,
        TenantId tenant,
        String label,
        BigDecimal monthlyQuotaCny,
        BigDecimal balanceCny,
        Status status,
        Instant createdAt) {

    public enum Status { ACTIVE, REVOKED }

    public VirtualKey {
        if (vkId == null || vkId.isBlank()) {
            throw new IllegalArgumentException("vkId must not be blank");
        }
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        if (tenant == null) {
            throw new IllegalArgumentException("tenant must not be null");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (monthlyQuotaCny == null || monthlyQuotaCny.signum() < 0) {
            throw new IllegalArgumentException("monthlyQuotaCny must be ≥ 0");
        }
        if (balanceCny == null || balanceCny.signum() < 0) {
            throw new IllegalArgumentException("balanceCny must be ≥ 0");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
    }
}