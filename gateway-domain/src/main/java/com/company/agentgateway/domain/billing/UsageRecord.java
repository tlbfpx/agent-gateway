package com.company.agentgateway.domain.billing;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单次 LLM 调用的 token 用量快照（spec §21.2 + D2 GW-QUOTA-001）。
 *
 * <p><b>关键约束</b>：cost 与 unitPriceIn/Out 必须同时落库（spec §21.2 强约束），
 * 模型单价变更后历史账单金额可重算，不依赖当前单价。
 */
public record UsageRecord(
        String recordId,
        TenantId tenant,
        UserId user,
        ModelId model,
        String agentName,
        Instant timestamp,
        long tokensIn,
        long tokensOut,
        BigDecimal cost,
        BigDecimal unitPriceIn,
        BigDecimal unitPriceOut) {
    public UsageRecord {
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalArgumentException("recordId must not be blank");
        }
        if (tenant == null) throw new IllegalArgumentException("tenant must not be null");
        if (user == null) throw new IllegalArgumentException("user must not be null");
        if (model == null) throw new IllegalArgumentException("model must not be null");
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("agentName must not be blank");
        }
        if (timestamp == null) throw new IllegalArgumentException("timestamp must not be null");
        if (tokensIn < 0) throw new IllegalArgumentException("tokensIn must be ≥ 0");
        if (tokensOut < 0) throw new IllegalArgumentException("tokensOut must be ≥ 0");
        if (cost == null || cost.signum() < 0) throw new IllegalArgumentException("cost must be ≥ 0");
        if (unitPriceIn == null || unitPriceIn.signum() < 0) throw new IllegalArgumentException("unitPriceIn must be ≥ 0");
        if (unitPriceOut == null || unitPriceOut.signum() < 0) throw new IllegalArgumentException("unitPriceOut must be ≥ 0");
    }
}
