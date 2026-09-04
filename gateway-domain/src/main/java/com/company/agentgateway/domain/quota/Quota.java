package com.company.agentgateway.domain.quota;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;

import java.util.Map;

/**
 * 租户级配额定义（spec §16.2 既有 record）。
 *
 * <p>字段：D1 之前的限流器定义（qpsLimit / dailyTokenBudget / modelSpecificLimits）。
 * D2 在此基础上新增 {@link QuotaPolicy} 三档策略（ALERT/THROTTLE/SUSPEND）。
 */
public record Quota(
        TenantId tenant,
        long qpsLimit,
        long dailyTokenBudget,
        Map<ModelId, Long> modelSpecificLimits) {
    public Quota {
        if (tenant == null) throw new IllegalArgumentException("tenant must not be null");
        if (qpsLimit < 0) throw new IllegalArgumentException("qpsLimit must be ≥ 0");
        if (dailyTokenBudget < 0) throw new IllegalArgumentException("dailyTokenBudget must be ≥ 0");
        modelSpecificLimits = modelSpecificLimits == null ? Map.of() : Map.copyOf(modelSpecificLimits);
    }
}
