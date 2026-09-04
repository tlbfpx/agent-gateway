package com.company.agentgateway.domain.billing;

/**
 * 预算告警百分比阈值（spec §21.4）：超阈值且 !alertSent 触发告警。
 */
public record AlertThreshold(int percent) {
    public AlertThreshold {
        if (percent < 1 || percent > 100) {
            throw new IllegalArgumentException("percent must be in [1, 100]");
        }
    }
}
