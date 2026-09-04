package com.company.agentgateway.infra.observability;

import com.company.agentgateway.domain.billing.BillingPort;
import com.company.agentgateway.domain.billing.UsageRecord;
import com.company.agentgateway.domain.observability.ObservabilityHooks;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * ObservabilityHooks 的 Micrometer 实现（spec §7.2 指标体系）。
 *
 * <p>指标（带 tenant/user/agent/model/channel 标签）：
 * <ul>
 *   <li>chat.requests（Counter）+ chat.latency（Timer/Histogram）+ chat.errors（Counter）</li>
 *   <li>agent.invocations（Counter，命中分布）+ agent.latency（Timer）+ agent.errors（Counter）</li>
 *   <li>llm.tokens.in/out（Counter，按 model 核算成本）</li>
 * </ul>
 *
 * <p><b>D2 GW-QUOTA-005</b>：onTokens 可选注入 {@link BillingPort}，
 * 实现单一数据源（spec §21.3）— 计量与计费同源。未注入时退化为纯计量（零破坏）。
 */
public class MicrometerObservabilityHooks implements ObservabilityHooks {

    private final MeterRegistry registry;
    private final BillingPort billingPort; // nullable：未装配时退化为纯计量

    public MicrometerObservabilityHooks(MeterRegistry registry) {
        this(registry, null);
    }

    public MicrometerObservabilityHooks(MeterRegistry registry, BillingPort billingPort) {
        this.registry = registry;
        this.billingPort = billingPort;
    }

    @Override
    public void onChatRequest(String tenant, String user, String model, String channel) {
        registry.counter("chat.requests",
                Tags.of("tenant", tag(tenant), "model", tag(model), "channel", tag(channel)))
                .increment();
    }

    @Override
    public void onChatComplete(String tenant, String model, long latencyMs, boolean success) {
        registry.timer("chat.latency", Tags.of("tenant", tag(tenant), "model", tag(model), "success", String.valueOf(success)))
                .record(java.time.Duration.ofMillis(latencyMs));
        if (!success) {
            registry.counter("chat.errors", Tags.of("tenant", tag(tenant), "model", tag(model))).increment();
        }
    }

    @Override
    public void onAgentInvoke(String tenant, String agentName, String model) {
        registry.counter("agent.invocations",
                Tags.of("tenant", tag(tenant), "agent", tag(agentName), "model", tag(model)))
                .increment();
    }

    @Override
    public void onAgentComplete(String tenant, String agentName, long latencyMs, boolean success) {
        registry.timer("agent.latency", Tags.of("tenant", tag(tenant), "agent", tag(agentName), "success", String.valueOf(success)))
                .record(java.time.Duration.ofMillis(latencyMs));
        if (!success) {
            registry.counter("agent.errors", Tags.of("tenant", tag(tenant), "agent", tag(agentName))).increment();
        }
    }

    @Override
    public void onTokens(String tenant, String model, long tokensIn, long tokensOut) {
        registry.counter("llm.tokens.in", Tags.of("tenant", tag(tenant), "model", tag(model))).increment(tokensIn);
        registry.counter("llm.tokens.out", Tags.of("tenant", tag(tenant), "model", tag(model))).increment(tokensOut);
        // D2 GW-QUOTA-005：单一数据源 — 计量同时喂计费（spec §21.3）
        if (billingPort != null) {
            try {
                long safeIn = Math.max(0, tokensIn);
                long safeOut = Math.max(0, tokensOut);
                billingPort.recordUsage(new UsageRecord(
                        "rt-" + UUID.randomUUID(),
                        new TenantId(tag(tenant)),
                        new UserId("d2-onTokens"), // 一期无 user 维度回调，占位
                        new ModelId(tag(model)),
                        "unknown", // 一期无 agent 维度回调，占位
                        Instant.now(),
                        safeIn, safeOut,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
            } catch (Exception e) {
                // spec §GW-QUOTA-005 失败容错：计费落账失败不阻断计量上报
            }
        }
    }

    @Override
    public void onError(String tenant, String code) {
        registry.counter("gateway.errors", Tags.of("tenant", tag(tenant), "code", tag(code))).increment();
    }

    @Override
    public void onWorkflowComplete(String tenant, String workflowName, String runId,
                                  long durationMs, boolean success) {
        // workflow. 前缀:被 PgMetricsPublisher 白名单收录(接 A 体系)
        registry.timer("workflow.run.duration",
                Tags.of("tenant", tag(tenant), "workflow", tag(workflowName),
                        "run_id", runId == null ? "?" : runId))
                .record(java.time.Duration.ofMillis(durationMs));
        if (success) {
            registry.counter("workflow.run.count",
                    Tags.of("tenant", tag(tenant), "workflow", tag(workflowName))).increment();
        } else {
            registry.counter("workflow.run.failed",
                    Tags.of("tenant", tag(tenant), "workflow", tag(workflowName))).increment();
        }
    }

    /** Micrometer 标签值不能为 null。 */
    private static String tag(String v) {
        return v == null ? "unknown" : v;
    }
}
