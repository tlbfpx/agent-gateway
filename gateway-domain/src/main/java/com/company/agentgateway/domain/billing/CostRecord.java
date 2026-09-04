package com.company.agentgateway.domain.billing;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 按日聚合成本记录（spec §21.2 + D2 GW-QUOTA-001）。
 *
 * <p>聚合键：tenant × user × model × agent × date 五元组 + 累计 token 与金额。
 */
public record CostRecord(
        String id,
        TenantId tenant,
        UserId user,
        ModelId model,
        String agentName,
        LocalDate date,
        long totalTokensIn,
        long totalTokensOut,
        BigDecimal totalCost,
        String currency) {
    public CostRecord {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (tenant == null) throw new IllegalArgumentException("tenant must not be null");
        if (user == null) throw new IllegalArgumentException("user must not be null");
        if (model == null) throw new IllegalArgumentException("model must not be null");
        if (date == null) throw new IllegalArgumentException("date must not be null");
        if (totalTokensIn < 0) throw new IllegalArgumentException("totalTokensIn must be ≥ 0");
        if (totalTokensOut < 0) throw new IllegalArgumentException("totalTokensOut must be ≥ 0");
        if (totalCost == null || totalCost.signum() < 0) throw new IllegalArgumentException("totalCost must be ≥ 0");
        if (currency == null) {
            currency = "CNY"; // 一期单币种默认值（proposal §决策点 D-3）
        } else if (currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
    }
}
