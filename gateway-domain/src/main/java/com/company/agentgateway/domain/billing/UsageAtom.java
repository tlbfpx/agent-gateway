package com.company.agentgateway.domain.billing;

import java.math.BigDecimal;

/**
 * 用量原子单位（spec §16.2 Quota 扣减维度）：单次 LLM 调用的实际用量。
 */
public record UsageAtom(long requests, long tokensIn, long tokensOut, BigDecimal cost) {
    public UsageAtom {
        if (requests < 0) throw new IllegalArgumentException("requests must be ≥ 0");
        if (tokensIn < 0) throw new IllegalArgumentException("tokensIn must be ≥ 0");
        if (tokensOut < 0) throw new IllegalArgumentException("tokensOut must be ≥ 0");
        if (cost == null || cost.signum() < 0) throw new IllegalArgumentException("cost must be ≥ 0");
    }
}
