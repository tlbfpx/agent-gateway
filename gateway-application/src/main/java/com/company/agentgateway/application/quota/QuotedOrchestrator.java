package com.company.agentgateway.application.quota;

import com.company.agentgateway.application.orchestration.ChatOrchestrator;
import com.company.agentgateway.application.orchestration.ChatRequest;
import com.company.agentgateway.application.orchestration.ChatStreamEvent;
import com.company.agentgateway.domain.billing.UsageAtom;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;

/**
 * ChatOrchestrator 装饰器（spec §21.3 + D2 GW-QUOTA-006）。
 *
 * <p><b>关键约束</b>：装饰器模式封装 QuotaGate 前置拦截，**不动 ChatOrchestrator
 * 既有方法签名与字段**（spec §归档闸门 ④ 既有测试零修改红线）。
 *
 * <p>调用链：客户端 → QuotedOrchestrator.orchestrate → QuotaGate.check（前置三维校验）
 * → ChatOrchestrator.orchestrate（既有链路，未修改）。
 *
 * <p>QuotaGate 放行（Allowed/Throttled）不阻断；Suspended/Rejected 抛
 * {@code QuotaExceededException}（HTTP 403/429 + GW-4305/4304）。
 * 真实消耗由 ObservabilityHooks.onTokens → BillingEngine 异步落账（spec §21.3 单一数据源）。
 */
public class QuotedOrchestrator {

    /** 预测用量：保守估计单次请求 1 call + 1000 in / 500 out + 0.01 元。 */
    static final UsageAtom PREDICTED_USAGE =
            new UsageAtom(1, 1000, 500, new BigDecimal("0.01"));

    private static final ModelId UNKNOWN_MODEL = new ModelId("unknown");

    private final ChatOrchestrator inner;
    private final QuotaGate quotaGate;

    public QuotedOrchestrator(ChatOrchestrator inner, QuotaGate quotaGate) {
        this.inner = inner;
        this.quotaGate = quotaGate;
    }

    public Flux<ChatStreamEvent> orchestrate(ChatRequest request, String apiKey) {
        return orchestrate(request, apiKey, null);
    }

    public Flux<ChatStreamEvent> orchestrate(ChatRequest request, String apiKey, String tenantIdHeader) {
        preCheck(resolveTenant(tenantIdHeader), request.modelOpt().orElse(UNKNOWN_MODEL));
        return inner.orchestrate(request, apiKey, tenantIdHeader);
    }

    /** 前置配额校验（包内可见，便于测试）。 */
    void preCheck(TenantId tenant, ModelId model) {
        quotaGate.check(tenant, model, PREDICTED_USAGE);
    }

    private static TenantId resolveTenant(String tenantIdHeader) {
        return new TenantId(tenantIdHeader == null || tenantIdHeader.isBlank() ? "primary" : tenantIdHeader);
    }
}
