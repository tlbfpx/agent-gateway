package com.company.agentgateway.domain.billing;

/**
 * 账单状态（spec §21.5 + D2 设计 §2.1）。
 */
public enum InvoiceStatus { DRAFT, FINALIZED, EXPORTED, RECONCILED }
