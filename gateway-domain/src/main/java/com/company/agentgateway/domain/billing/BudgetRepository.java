package com.company.agentgateway.domain.billing;

import com.company.agentgateway.domain.shared.TenantId;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 出站端口：预算管理（spec §21.4 · D2 GW-QUOTA-002）。
 *
 * <p>与 BillingPort 解耦（避免配额误用计费语义）；二期 SUSPEND 落地需要
 * 扩展为包含 suspended_until 字段（spec §21.4 二期）。
 */
public interface BudgetRepository {

    /** 按租户查询预算。 */
    Optional<Budget> findByTenant(TenantId tenant);

    /** 保存（upsert：存在则覆盖）。 */
    void save(Budget budget);

    /** 删除预算。 */
    void delete(TenantId tenant);

    /** 标记 alertSent=true（幂等：第二次返回 false）。 */
    boolean markAlertSent(TenantId tenant);

    /** 累加用量（currentDailyUsed + currentMonthlyUsed 同步累加）。 */
    void accumulateUsage(TenantId tenant, BigDecimal amount);
}
