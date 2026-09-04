package com.company.agentgateway.domain.billing;

import com.company.agentgateway.domain.shared.TenantId;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * 周期账单（spec §21.5）：自然月维度，按 tenant × month 分组。
 */
public record Invoice(
        String id,
        TenantId tenant,
        YearMonth period,
        InvoiceStatus status,
        BigDecimal totalCost,
        List<InvoiceLineItem> lines,
        String currency) {
    public Invoice {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (tenant == null) throw new IllegalArgumentException("tenant must not be null");
        if (period == null) throw new IllegalArgumentException("period must not be null");
        if (status == null) throw new IllegalArgumentException("status must not be null");
        if (totalCost == null || totalCost.signum() < 0) {
            throw new IllegalArgumentException("totalCost must be ≥ 0");
        }
        lines = lines == null ? List.of() : List.copyOf(lines);
        if (currency == null) currency = "CNY";
        else if (currency.isBlank()) throw new IllegalArgumentException("currency must not be blank");
    }
}
