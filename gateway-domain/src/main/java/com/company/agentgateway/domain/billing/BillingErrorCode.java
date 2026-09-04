package com.company.agentgateway.domain.billing;

/**
 * D2 错误码常量集中类（spec §13.4 + D2 GW-QUOTA-008 + roadmap §3 段位零冲突）。
 *
 * <p>D2 占 GW-43xx（4301~4306），与 D1 GW-1xxx/42xx、D3 GW-5xxx、D4 GW-45xx/6xxx/7xxx 零冲突。
 */
public final class BillingErrorCode {

    /** 账单查询参数非法。GET /v1/admin/billing/costs 缺 from/to → 400。 */
    public static final String BILLING_QUERY_INVALID = "GW-4301";

    /** 预算配置冲突（日预算 > 月预算）。POST /budgets → 400。 */
    public static final String BUDGET_CONFIG_CONFLICT = "GW-4302";

    /** 账单导出失败（对象存储不可用）。GET /usage/export → 500。 */
    public static final String BILLING_EXPORT_FAILED = "GW-4303";

    /** 配额硬上限触发，拒绝请求。QuotaGate Rejected → 429。 */
    public static final String QUOTA_HARD_LIMIT = "GW-4304";

    /** 租户被 SUSPEND，请求被拒。QuotaGate Suspended → 403。 */
    public static final String TENANT_SUSPENDED = "GW-4305";

    /** 配额策略非法（policy 取值不在白名单）。POST /budgets → 400。 */
    public static final String QUOTA_POLICY_INVALID = "GW-4306";

    private BillingErrorCode() {
        // no instances
    }
}
