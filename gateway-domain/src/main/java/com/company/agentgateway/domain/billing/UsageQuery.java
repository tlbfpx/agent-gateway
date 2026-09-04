package com.company.agentgateway.domain.billing;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;

import java.time.Instant;

/**
 * 用量查询条件（spec §21.6）。
 *
 * <p>tenant 必填（租户隔离），其他条件为可选过滤。
 */
public record UsageQuery(TenantId tenant, Instant from, Instant to, ModelId model, String agentName) {
    public UsageQuery {
        if (tenant == null) throw new IllegalArgumentException("tenant must not be null");
    }
}
