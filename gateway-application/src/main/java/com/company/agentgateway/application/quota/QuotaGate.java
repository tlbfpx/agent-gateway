package com.company.agentgateway.application.quota;

import com.company.agentgateway.domain.billing.QuotaExceededException;
import com.company.agentgateway.domain.billing.UsageAtom;
import com.company.agentgateway.domain.quota.QuotaDecision;
import com.company.agentgateway.domain.quota.QuotaDimension;
import com.company.agentgateway.domain.quota.QuotaKey;
import com.company.agentgateway.domain.quota.QuotaPort;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

/**
 * 配额前置拦截（spec §16.2 + D2 GW-QUOTA-006）。
 *
 * <p>调用链：编排层 LLM 调用前 → {@link #check} → 4 decision 映射：
 * <ul>
 *   <li>{@link QuotaDecision.Allowed} → 放行</li>
 *   <li>{@link QuotaDecision.Throttled} → 放行（应用节流配置，不阻断）</li>
 *   <li>{@link QuotaDecision.Suspended} → throw {@code QuotaExceededException("GW-4305")} → HTTP 403</li>
 *   <li>{@link QuotaDecision.Rejected} → throw {@code QuotaExceededException("GW-4304")} → HTTP 429</li>
 * </ul>
 *
 * <p>三维独立判定：REQUEST / MODEL_TOKEN / MONEY 任一维度 Rejected/Suspended 即阻断（短路）。
 */
public class QuotaGate {

    private static final Logger log = LoggerFactory.getLogger(QuotaGate.class);

    private final QuotaPort quotaPort;

    public QuotaGate(QuotaPort quotaPort) {
        this.quotaPort = quotaPort;
    }

    /**
     * 前置三维校验。
     *
     * @throws QuotaExceededException 当任一维度决策为 Rejected（GW-4304）或 Suspended（GW-4305）
     */
    public void check(TenantId tenant, ModelId model, UsageAtom predicted) {
        // 三维独立判定（REQUEST / MODEL_TOKEN / MONEY），短路求值
        QuotaDecision requestDecision = quotaPort.check(
                new QuotaKey(tenant, model, QuotaDimension.REQUEST),
                new UsageAtom(predicted.requests(), 0, 0, BigDecimal.ZERO));
        enforce(requestDecision, "REQUEST", tenant, model);

        QuotaDecision tokenDecision = quotaPort.check(
                new QuotaKey(tenant, model, QuotaDimension.MODEL_TOKEN),
                new UsageAtom(0, predicted.tokensIn(), predicted.tokensOut(), BigDecimal.ZERO));
        enforce(tokenDecision, "MODEL_TOKEN", tenant, model);

        QuotaDecision moneyDecision = quotaPort.check(
                new QuotaKey(tenant, model, QuotaDimension.MONEY),
                new UsageAtom(0, 0, 0, predicted.cost()));
        enforce(moneyDecision, "MONEY", tenant, model);
    }

    private void enforce(QuotaDecision decision, String dim, TenantId tenant, ModelId model) {
        switch (decision) {
            case QuotaDecision.Allowed a -> { /* 放行 */ }
            case QuotaDecision.Throttled t -> {
                // Throttled 不阻断（spec §GW-QUOTA-006 应用节流配置）
                log.debug("QuotaGate throttled tenant={} model={} dim={} newQpsPct={} duration={}",
                        tenant.value(), model.value(), dim, t.newQpsPercent(), t.duration());
            }
            case QuotaDecision.Suspended s ->
                throw new QuotaExceededException("GW-4305",
                        "tenant " + tenant.value() + " suspended: " + s.reason());
            case QuotaDecision.Rejected r ->
                throw new QuotaExceededException("GW-4304",
                        "quota " + r.quotaDimension() + " exhausted (limit=" + r.limit()
                                + ", used=" + r.used() + ")");
        }
    }
}
