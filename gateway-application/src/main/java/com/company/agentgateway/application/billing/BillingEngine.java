package com.company.agentgateway.application.billing;

import com.company.agentgateway.domain.billing.BillingPort;
import com.company.agentgateway.domain.billing.UsageQuery;
import com.company.agentgateway.domain.billing.UsageRecord;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 计费引擎（spec §21.3 + D2 GW-QUOTA-001 / GW-QUOTA-005）。
 *
 * <p>调用链：观测钩子 onTokens → BillingEngine.recordUsage（单价快照）→ BillingPort.recordUsage 落账。
 *
 * <p>单价来源（spec §5.5.2 ModelDef.costPer1k{In,Out}）：
 * <ul>
 *   <li>通过 {@link ModelPriceRegistry} 函数式注入查询（避免循环依赖 ModelDef）</li>
 *   <li>未知 model 回退 zero price（不抛异常，spec §GW-QUOTA-005 失败容错）</li>
 * </ul>
 */
public class BillingEngine {

    private static final Logger log = LoggerFactory.getLogger(BillingEngine.class);

    private final BillingPort billingPort;
    private final ModelPriceRegistry modelPriceRegistry;
    /** 可选：落账后触发预算校验（design §4.1 + GW-QUOTA-007）。 */
    private final BudgetGuard budgetGuard;

    public BillingEngine(BillingPort billingPort, ModelPriceRegistry modelPriceRegistry) {
        this(billingPort, modelPriceRegistry, null);
    }

    public BillingEngine(BillingPort billingPort, ModelPriceRegistry modelPriceRegistry,
                         BudgetGuard budgetGuard) {
        this.billingPort = billingPort;
        this.modelPriceRegistry = modelPriceRegistry;
        this.budgetGuard = budgetGuard;
    }

    /** 记录单次 LLM 调用的 token 用量 + 单价快照 + 成本。 */
    public void recordUsage(TenantId tenant, UserId user, ModelId model, String agentName,
                            long tokensIn, long tokensOut) {
        long safeIn = Math.max(0, tokensIn);
        long safeOut = Math.max(0, tokensOut);
        ModelId.Price price = modelPriceRegistry.priceOf(model);
        BigDecimal cost;
        BigDecimal unitIn;
        BigDecimal unitOut;
        if (price == null) {
            cost = BigDecimal.ZERO;
            unitIn = BigDecimal.ZERO;
            unitOut = BigDecimal.ZERO;
        } else {
            cost = new BigDecimal(safeIn).multiply(price.priceIn())
                    .add(new BigDecimal(safeOut).multiply(price.priceOut()));
            unitIn = price.priceIn();
            unitOut = price.priceOut();
        }
        UsageRecord record = new UsageRecord(
                "rt-" + UUID.randomUUID(),
                tenant, user, model, agentName,
                Instant.now(),
                safeIn, safeOut, cost, unitIn, unitOut);
        try {
            billingPort.recordUsage(record);
        } catch (Exception e) {
            log.warn("BillingEngine recordUsage failed (swallowed): {}", e.getMessage());
        }
        // 落账后触发预算校验（design §4.1；BudgetGuard 内部自带失败容错，不阻断主链）
        if (budgetGuard != null && cost.signum() > 0) {
            budgetGuard.onUsageAccumulated(tenant, cost);
        }
    }

    /** 聚合查询：转给 BillingPort.queryCost。 */
    public BigDecimal totalCost(UsageQuery query) {
        return billingPort.queryCost(query);
    }

    /** 单价注册表（函数式注入，避免与现有 ModelDef 紧耦合）。 */
    @FunctionalInterface
    public interface ModelPriceRegistry {
        ModelId.Price priceOf(ModelId modelId);
    }
}
