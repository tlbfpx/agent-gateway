package com.company.agentgateway.domain.billing;

import com.company.agentgateway.domain.shared.ModelId;

import java.math.BigDecimal;

/**
 * 账单行项目（spec §21.5）：一行 = 一个 (model × agent) 组合。
 */
public record InvoiceLineItem(
        ModelId model,
        String agentName,
        long totalTokensIn,
        long totalTokensOut,
        BigDecimal subtotal) {
    public InvoiceLineItem {
        if (model == null) throw new IllegalArgumentException("model must not be null");
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("agentName must not be blank");
        }
        if (totalTokensIn < 0) throw new IllegalArgumentException("totalTokensIn must be ≥ 0");
        if (totalTokensOut < 0) throw new IllegalArgumentException("totalTokensOut must be ≥ 0");
        if (subtotal == null || subtotal.signum() < 0) {
            throw new IllegalArgumentException("subtotal must be ≥ 0");
        }
    }
}
