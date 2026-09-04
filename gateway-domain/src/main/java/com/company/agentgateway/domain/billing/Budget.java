package com.company.agentgateway.domain.billing;

import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 租户级预算（spec §21.2 + §21.4）。
 *
 * <p><b>SUSPEND 冷静期约束</b>（D2 决策点 D-5）：suspendUntil 必须 future，
 * suspendAction=SUSPEND 必填；自动策略只到 THROTTLE（spec §21.4 强约束）。
 *
 * <p><b>超限动作</b>（P1 预算告警接线）：overLimitAction=BLOCK（默认，现状 429 拒绝）；
 * DOWNGRADE 时配额超限由 ChatOrchestrator 降级到 fallbackModel 而非直接拒绝。
 */
public record Budget(
        TenantId tenant,
        UserId user,
        BudgetType type,
        BigDecimal dailyLimit,
        BigDecimal monthlyLimit,
        BigDecimal currentDailyUsed,
        BigDecimal currentMonthlyUsed,
        AlertThreshold alertThreshold,
        boolean alertSent,
        QuotaAction suspendAction,
        Instant suspendUntil,
        OverLimitAction overLimitAction,
        String fallbackModel) {

    public enum QuotaAction { ALERT, THROTTLE, SUSPEND }

    /** 超限动作：BLOCK=拒绝（429，现状）；DOWNGRADE=降级到 fallbackModel。 */
    public enum OverLimitAction { BLOCK, DOWNGRADE }

    /** 兼容旧签名（overLimitAction 缺省 BLOCK）。 */
    public Budget(TenantId tenant, UserId user, BudgetType type,
                  BigDecimal dailyLimit, BigDecimal monthlyLimit,
                  BigDecimal currentDailyUsed, BigDecimal currentMonthlyUsed,
                  AlertThreshold alertThreshold, boolean alertSent,
                  QuotaAction suspendAction, Instant suspendUntil) {
        this(tenant, user, type, dailyLimit, monthlyLimit, currentDailyUsed,
                currentMonthlyUsed, alertThreshold, alertSent, suspendAction,
                suspendUntil, null, null);
    }

    public Budget {
        if (tenant == null) throw new IllegalArgumentException("tenant must not be null");
        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (dailyLimit == null || dailyLimit.signum() < 0) {
            throw new IllegalArgumentException("dailyLimit must be ≥ 0");
        }
        if (monthlyLimit == null || monthlyLimit.signum() < 0) {
            throw new IllegalArgumentException("monthlyLimit must be ≥ 0");
        }
        if (currentDailyUsed == null || currentDailyUsed.signum() < 0) {
            throw new IllegalArgumentException("currentDailyUsed must be ≥ 0");
        }
        if (currentMonthlyUsed == null || currentMonthlyUsed.signum() < 0) {
            throw new IllegalArgumentException("currentMonthlyUsed must be ≥ 0");
        }
        if (alertThreshold == null) throw new IllegalArgumentException("alertThreshold must not be null");
        // SUSPEND 冷静期约束（spec §21.4 + D2 决策点 D-5）
        if (suspendAction == QuotaAction.SUSPEND) {
            if (suspendUntil == null) {
                throw new IllegalArgumentException("suspendUntil must be set when suspendAction=SUSPEND");
            }
            if (suspendUntil.isBefore(Instant.now())) {
                throw new IllegalArgumentException("suspendUntil must be future");
            }
        } else {
            if (suspendUntil != null) {
                throw new IllegalArgumentException("suspendUntil requires suspendAction=SUSPEND");
            }
        }
        // 超限动作规范化 + DOWNGRADE 约束
        if (overLimitAction == null) overLimitAction = OverLimitAction.BLOCK;
        if (overLimitAction == OverLimitAction.DOWNGRADE) {
            if (fallbackModel == null || fallbackModel.isBlank()) {
                throw new IllegalArgumentException("fallbackModel must be set when overLimitAction=DOWNGRADE");
            }
        }
    }
}
