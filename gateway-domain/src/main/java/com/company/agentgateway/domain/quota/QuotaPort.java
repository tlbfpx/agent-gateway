package com.company.agentgateway.domain.quota;

import com.company.agentgateway.domain.billing.UsageAtom;
import com.company.agentgateway.domain.shared.TenantId;

import java.util.List;

/**
 * 出站端口：配额查询/扣减（spec §16.2 + D2 GW-QUOTA-002）。
 *
 * <p>所有方法租户隔离（{@link QuotaKey#tenant()} 为第一约束）。
 *
 * <p>典型流程：check() → Allowed → 放行 + 后置 consume()；失败 reverse() 回滚。
 */
public interface QuotaPort {

    /** 预检（不扣减）：返回当前配额决策。 */
    QuotaDecision check(QuotaKey key, UsageAtom predicted);

    /** 后置扣减：实际用量累加到计数器。 */
    void consume(QuotaKey key, UsageAtom used);

    /** 失败回滚：撤销已扣减。 */
    void reverse(QuotaKey key, UsageAtom used);

    /** 管理后台读快照：返回该租户的所有维度当前剩余配额。 */
    List<QuotaDecision> snapshot(TenantId tenant);
}
