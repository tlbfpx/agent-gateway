package com.company.agentgateway.domain.quota;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;

import java.math.BigDecimal;

/**
 * 配额策略（spec §21.4 + D2 GW-QUOTA-004）：租户 × 模型 × 维度 × 策略动作 + 阈值 + 限值。
 *
 * <p>SUSPEND 必须 limitValue &gt; 0；非法 policy 取值返回 GW-4306。
 */
public record QuotaPolicy(
        TenantId tenant,
        ModelId model,
        QuotaDimension dimension,
        Action policy,
        int thresholdPct,
        BigDecimal limitValue) {

    public enum Action { ALERT, THROTTLE, SUSPEND }

    public QuotaPolicy {
        if (tenant == null) throw new IllegalArgumentException("tenant must not be null");
        if (model == null) throw new IllegalArgumentException("model must not be null");
        if (dimension == null) throw new IllegalArgumentException("dimension must not be null");
        if (policy == null) throw new IllegalArgumentException("policy must not be null");
        if (thresholdPct < 1 || thresholdPct > 100) {
            throw new IllegalArgumentException("thresholdPct must be in [1, 100]");
        }
        if (limitValue == null || limitValue.signum() < 0) {
            throw new IllegalArgumentException("limitValue must be ≥ 0");
        }
        if (policy == Action.SUSPEND && limitValue.signum() <= 0) {
            throw new IllegalArgumentException("SUSPEND requires limitValue > 0");
        }
    }
}
