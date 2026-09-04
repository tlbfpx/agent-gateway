package com.company.agentgateway.domain.billing;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 出站端口：成本核算存储（spec §21）。由 infra 实现（InMemory 默认 / Redis·DB 二期）。
 * 单一数据源：UsageRecord 记录每次 LLM 调用的 token 用量 + 成本（token×单价）。
 */
public interface CostRepository {

    /** 记录一次用量（成本写入器调用）。 */
    void recordUsage(UsageRecord record);

    /** 按租户/日期范围聚合成本。 */
    List<CostSummary> queryCosts(TenantId tenant, LocalDate from, LocalDate to);

    /** 获取或创建预算（默认无预算=无限额）。 */
    Budget getBudget(TenantId tenant);
    void setBudget(Budget budget);

    /** 单次用量记录（spec §21.2）。 */
    record UsageRecord(String recordId, TenantId tenant, UserId user, ModelId model,
                       String agentName, Instant timestamp,
                       long tokensIn, long tokensOut, BigDecimal cost) {}

    /** 成本聚合（按 tenant×model×date）。 */
    record CostSummary(TenantId tenant, ModelId model, LocalDate date,
                       long totalTokensIn, long totalTokensOut, BigDecimal totalCost) {}

    /** 预算（spec §21.4）。 */
    record Budget(TenantId tenant,
                  long dailyTokenLimit, long monthlyTokenLimit,
                  BigDecimal dailyCostLimit, BigDecimal monthlyCostLimit,
                  long currentDailyTokens, BigDecimal currentDailyCost,
                  boolean alertSent) {}
}
