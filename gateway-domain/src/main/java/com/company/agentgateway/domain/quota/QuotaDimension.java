package com.company.agentgateway.domain.quota;

/**
 * 配额维度（spec §16.2）：REQUEST / MODEL_TOKEN / MONEY 三维。
 */
public enum QuotaDimension {
    REQUEST,
    MODEL_TOKEN,
    MONEY
}
