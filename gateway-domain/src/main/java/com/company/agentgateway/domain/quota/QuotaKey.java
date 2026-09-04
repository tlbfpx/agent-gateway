package com.company.agentgateway.domain.quota;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;

/**
 * 配额键（spec §16.2）：租户 × 模型 × 维度三元组。
 */
public record QuotaKey(TenantId tenant, ModelId model, QuotaDimension dimension) {
    public QuotaKey {
        if (tenant == null) throw new IllegalArgumentException("tenant must not be null");
        if (model == null) throw new IllegalArgumentException("model must not be null");
        if (dimension == null) throw new IllegalArgumentException("dimension must not be null");
    }
}
