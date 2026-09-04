package com.company.agentgateway.domain.billing;

import java.math.BigDecimal;
import java.util.List;

/**
 * 出站端口：计费数据访问（spec §21.6 + D2 GW-QUOTA-002）。
 *
 * <p>所有方法租户隔离（{@link UsageQuery#tenant()} 为第一约束）。
 */
public interface BillingPort {

    /** 落账：单次 LLM 调用的 token 用量 + 单价快照 + 成本。 */
    void recordUsage(UsageRecord record);

    /** 查询用量记录（按 tenant + 可选 model/agent/date 过滤）。 */
    List<UsageRecord> queryUsage(UsageQuery query);

    /** 查询累计成本（CNY 同币种聚合）。 */
    BigDecimal queryCost(UsageQuery query);

    /** 导出（spec §21.5 Chargeback；一期 CSV）。 */
    List<UsageRecord> exportUsage(UsageQuery query, ExportFormat format);
}
