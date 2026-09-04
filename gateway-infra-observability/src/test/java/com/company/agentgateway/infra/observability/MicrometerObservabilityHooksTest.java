package com.company.agentgateway.infra.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerObservabilityHooksTest {

    private SimpleMeterRegistry registry;
    private MicrometerObservabilityHooks hooks;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        hooks = new MicrometerObservabilityHooks(registry);
    }

    @Test
    void onChatRequest_递增chat_requests_counter() {
        hooks.onChatRequest("t1", "u1", "qwen", "API_KEY");
        hooks.onChatRequest("t1", "u1", "qwen", "API_KEY");
        assertThat(registry.counter("chat.requests",
                io.micrometer.core.instrument.Tags.of("tenant", "t1", "model", "qwen", "channel", "API_KEY")).count())
                .isEqualTo(2.0);
    }

    @Test
    void onChatComplete_成功_记录latency不递增errors() {
        hooks.onChatComplete("t1", "qwen", 150, true);
        assertThat(registry.timer("chat.latency",
                io.micrometer.core.instrument.Tags.of("tenant", "t1", "model", "qwen", "success", "true")).count())
                .isEqualTo(1);
        // chat.errors 不存在（成功）
        assertThat(registry.find("chat.errors").counters()).isEmpty();
    }

    @Test
    void onChatComplete_失败_递增errors() {
        hooks.onChatComplete("t1", "qwen", 100, false);
        assertThat(registry.counter("chat.errors",
                io.micrometer.core.instrument.Tags.of("tenant", "t1", "model", "qwen")).count())
                .isEqualTo(1.0);
    }

    @Test
    void onAgentInvoke_递增invocations_带agent标签() {
        hooks.onAgentInvoke("t1", "hr-agent", "qwen");
        assertThat(registry.counter("agent.invocations",
                io.micrometer.core.instrument.Tags.of("tenant", "t1", "agent", "hr-agent", "model", "qwen")).count())
                .isEqualTo(1.0);
    }

    @Test
    void onAgentComplete_失败_递增agent_errors() {
        hooks.onAgentComplete("t1", "hr-agent", 200, false);
        assertThat(registry.counter("agent.errors",
                io.micrometer.core.instrument.Tags.of("tenant", "t1", "agent", "hr-agent")).count())
                .isEqualTo(1.0);
    }

    @Test
    void onTokens_记录tokenInOut() {
        hooks.onTokens("t1", "qwen", 100, 50);
        assertThat(registry.counter("llm.tokens.in",
                io.micrometer.core.instrument.Tags.of("tenant", "t1", "model", "qwen")).count())
                .isEqualTo(100.0);
        assertThat(registry.counter("llm.tokens.out",
                io.micrometer.core.instrument.Tags.of("tenant", "t1", "model", "qwen")).count())
                .isEqualTo(50.0);
    }

    @Test
    void onError_递增gateway_errors_带code标签() {
        hooks.onError("t1", "A2A_TIMEOUT");
        assertThat(registry.counter("gateway.errors",
                io.micrometer.core.instrument.Tags.of("tenant", "t1", "code", "A2A_TIMEOUT")).count())
                .isEqualTo(1.0);
    }

    @Test
    void null标签值用unknown替代() {
        hooks.onChatRequest(null, null, null, null);
        assertThat(registry.counter("chat.requests",
                io.micrometer.core.instrument.Tags.of("model", "unknown", "channel", "unknown", "tenant", "unknown")).count())
                .isEqualTo(1.0);
    }

    // ====== D2 新增：单一数据源（spec §21.3 + GW-QUOTA-005） ======

    @Test
    void onTokens_callsBillingPortRecordUsage() {
        com.company.agentgateway.domain.billing.BillingPort billing =
                org.mockito.Mockito.mock(com.company.agentgateway.domain.billing.BillingPort.class);
        var hooked = new MicrometerObservabilityHooks(registry, billing);
        hooked.onTokens("t1", "m1", 100L, 50L);
        org.mockito.ArgumentCaptor<com.company.agentgateway.domain.billing.UsageRecord> cap =
                org.mockito.ArgumentCaptor.forClass(com.company.agentgateway.domain.billing.UsageRecord.class);
        org.mockito.Mockito.verify(billing, org.mockito.Mockito.times(1)).recordUsage(cap.capture());
        var rec = cap.getValue();
        org.assertj.core.api.Assertions.assertThat(rec.tokensIn()).isEqualTo(100L);
        org.assertj.core.api.Assertions.assertThat(rec.tokensOut()).isEqualTo(50L);
        org.assertj.core.api.Assertions.assertThat(rec.tenant().value()).isEqualTo("t1");
        org.assertj.core.api.Assertions.assertThat(rec.model().value()).isEqualTo("m1");
    }

    @Test
    void onTokens_billingFailure_doesNotPropagate() {
        com.company.agentgateway.domain.billing.BillingPort billing =
                org.mockito.Mockito.mock(com.company.agentgateway.domain.billing.BillingPort.class);
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(billing).recordUsage(org.mockito.ArgumentMatchers.any());
        var hooked = new MicrometerObservabilityHooks(registry, billing);
        // 不抛 + Meter 仍打（spec §GW-QUOTA-005 失败容错）
        org.assertj.core.api.Assertions.assertThatCode(() -> hooked.onTokens("t1", "m1", 100, 50))
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(registry.counter("llm.tokens.in",
                io.micrometer.core.instrument.Tags.of("tenant", "t1", "model", "m1")).count())
                .isEqualTo(100.0);
    }

    @Test
    void onTokens_withoutBillingPort_degradesToMetricsOnly() {
        // 单参构造器（既有兼容路径）：不触发计费
        var hooked = new MicrometerObservabilityHooks(registry);
        org.assertj.core.api.Assertions.assertThatCode(() -> hooked.onTokens("t1", "m1", 10, 5))
                .doesNotThrowAnyException();
    }
}
