package com.company.agentgateway.domain.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceDiffServiceTest {

    @Test
    @DisplayName("identical:5 字段全 same,响应相似度 = 1.0")
    void identical() {
        var a = new TraceDiffService.TraceSnapshot("t1", "gpt-4o", 100, 200, 1500L, 3,
                List.of("agent-a"), "hello world");
        var b = new TraceDiffService.TraceSnapshot("t2", "gpt-4o", 100, 200, 1500L, 3,
                List.of("agent-a"), "hello world");
        var r = TraceDiffService.diff(a, b);
        assertThat(r.verdict()).isEqualTo("identical");
        assertThat(r.responseSimilarity()).isEqualTo(1.0);
        assertThat(r.fieldDiffs()).allMatch(d -> "same".equals(d.verdict()));
        assertThat(r.toolSetAdded()).isEmpty();
        assertThat(r.toolSetRemoved()).isEmpty();
    }

    @Test
    @DisplayName("model 切换:verdict=diff,verdict=very_different 若响应完全不重合")
    void modelSwitch() {
        var a = new TraceDiffService.TraceSnapshot("t1", "deepseek", 100, 200, 1500L, 3,
                List.of("agent-a"), "今天天气很好");
        var b = new TraceDiffService.TraceSnapshot("t2", "gpt-4o", 120, 250, 1800L, 3,
                List.of("agent-a"), "the weather is sunny today");
        var r = TraceDiffService.diff(a, b);
        // tokens/latency/model 都 diff
        assertThat(r.fieldDiffs()).filteredOn(d -> "diff".equals(d.verdict()))
                .extracting(TraceDiffService.FieldDiff::field)
                .contains("tokensIn", "tokensOut", "latencyMs", "model");
        // 响应相似度低(中英文完全不同)
        assertThat(r.responseSimilarity()).isLessThan(0.5);
        assertThat(r.verdict()).isIn("different", "very_different");
    }

    @Test
    @DisplayName("tool 集合增删:add/remove 正确识别")
    void toolSetDiff() {
        var a = new TraceDiffService.TraceSnapshot("t1", "gpt-4o", 100, 200, 1500L, 3,
                List.of("tool-a", "tool-b"), "x");
        var b = new TraceDiffService.TraceSnapshot("t2", "gpt-4o", 100, 200, 1500L, 3,
                List.of("tool-b", "tool-c"), "x");
        var r = TraceDiffService.diff(a, b);
        assertThat(r.toolSetAdded()).containsExactly("tool-c");
        assertThat(r.toolSetRemoved()).containsExactly("tool-a");
    }

    @Test
    @DisplayName("Levenshtein:同字符串 → 1.0,空字符串 vs 非空 → 0.0")
    void similarity() {
        assertThat(TraceDiffService.similarity("hello", "hello")).isEqualTo(1.0);
        assertThat(TraceDiffService.similarity("", "")).isEqualTo(1.0);
        assertThat(TraceDiffService.similarity("", "abc")).isEqualTo(0.0);
        assertThat(TraceDiffService.similarity(null, null)).isEqualTo(1.0);
        assertThat(TraceDiffService.similarity(null, "x")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("相似度梯度:1 字符差 11 字符总长度 → ~0.91")
    void similarityGradient() {
        double s = TraceDiffService.similarity("hello world", "hello worlD"); // 1 char diff
        assertThat(s).isBetween(0.85, 0.95);
    }

    @Test
    @DisplayName("message_count delta:正负值正确")
    void messageCountDelta() {
        var a = new TraceDiffService.TraceSnapshot("t1", "m", 0, 0, 0L, 3, List.of(), "x");
        var b = new TraceDiffService.TraceSnapshot("t2", "m", 0, 0, 0L, 7, List.of(), "x");
        assertThat(TraceDiffService.diff(a, b).messageCountDelta()).isEqualTo(4);
        assertThat(TraceDiffService.diff(b, a).messageCountDelta()).isEqualTo(-4);
    }

    @Test
    @DisplayName("ReplayRequest 校验:traceId 空 → 抛 IllegalArgumentException")
    void requestValidation() {
        try {
            new ReplayRequest("", null, true, false, null, null);
            assertThat(false).as("should throw").isTrue();
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("traceId");
        }
    }

    @Test
    @DisplayName("ReplayOverrides.empty():7 个字段全 null")
    void emptyOverrides() {
        var o = ReplayRequest.ReplayOverrides.empty();
        assertThat(o.model()).isNull();
        assertThat(o.temperature()).isNull();
        assertThat(o.topP()).isNull();
        assertThat(o.maxTokens()).isNull();
        assertThat(o.messages()).isNull();
        assertThat(o.tools()).isNull();
        assertThat(o.system()).isNull();
    }

    @Test
    @DisplayName("Sprint 2 P4:SpanSnapshot.fromSpans 从 span attributes 合成")
    void snapshotFromSpans() {
        var spans = List.of(
                new TraceDiffService.SpanView("gateway.chat", 1000L, 2500L,
                        java.util.Map.of("response", "hello world", "tenant", "t1")),
                new TraceDiffService.SpanView("llm.call", 1100L, 2000L,
                        java.util.Map.of("model", "gpt-4o", "tokens_in", "10", "tokens_out", "20")),
                new TraceDiffService.SpanView("agent.call", 1200L, 1500L,
                        java.util.Map.of("agent_name", "weather-agent")),
                new TraceDiffService.SpanView("agent.call", 1300L, 1400L,
                        java.util.Map.of("agent_name", "db-agent"))
        );
        var snap = TraceDiffService.TraceSnapshot.fromSpans("trace-1", spans);
        assertThat(snap.traceId()).isEqualTo("trace-1");
        assertThat(snap.model()).isEqualTo("gpt-4o");
        assertThat(snap.tokensIn()).isEqualTo(10);
        assertThat(snap.tokensOut()).isEqualTo(20);
        assertThat(snap.latencyMs()).isEqualTo(1500L);  // 2500 - 1000
        assertThat(snap.tools()).containsExactlyInAnyOrder("weather-agent", "db-agent");
        assertThat(snap.responseText()).isEqualTo("hello world");
    }

    @Test
    @DisplayName("fromSpans:空 spans 返回空 snapshot")
    void snapshotFromEmptySpans() {
        var snap = TraceDiffService.TraceSnapshot.fromSpans("trace-1", List.of());
        assertThat(snap.model()).isNull();
        assertThat(snap.tokensIn()).isNull();
        assertThat(snap.latencyMs()).isEqualTo(0L);
        assertThat(snap.tools()).isEmpty();
    }

    @Test
    @DisplayName("Sprint 2 P2.4:spans 缺 token → 从 metrics 补全")
    void metricsFallbackForTokens() {
        // spans 中 llm.call 缺 tokens_in/out,只有 model
        var spans = List.of(
                new TraceDiffService.SpanView("llm.call", 1000L, 2000L,
                        java.util.Map.of("model", "gpt-4o"))  // 无 tokens 字段
        );
        // metrics 提供 (123, 456)
        MetricsQueryPort metrics = traceId ->
                java.util.Optional.of(new MetricsQueryPort.Tokens(123, 456));

        var snap = TraceDiffService.TraceSnapshot.fromSpans("trace-1", spans, metrics);
        assertThat(snap.tokensIn()).isEqualTo(123);
        assertThat(snap.tokensOut()).isEqualTo(456);
        assertThat(snap.model()).isEqualTo("gpt-4o");
    }

    @Test
    @DisplayName("Sprint 2 P2.4:spans 已含 token → 不被 metrics 覆盖")
    void spansTokensWinOverMetrics() {
        var spans = List.of(
                new TraceDiffService.SpanView("llm.call", 1000L, 2000L,
                        java.util.Map.of("model", "gpt-4o", "tokens_in", "999", "tokens_out", "888"))
        );
        MetricsQueryPort metrics = traceId ->
                java.util.Optional.of(new MetricsQueryPort.Tokens(1, 2));

        var snap = TraceDiffService.TraceSnapshot.fromSpans("trace-1", spans, metrics);
        assertThat(snap.tokensIn()).isEqualTo(999);  // spans 优先
        assertThat(snap.tokensOut()).isEqualTo(888);
    }

    @Test
    @DisplayName("Sprint 2 P2.4:metrics 返回空 → token 仍为 null")
    void metricsEmptyKeepsNull() {
        var spans = List.of(
                new TraceDiffService.SpanView("llm.call", 1000L, 2000L,
                        java.util.Map.of("model", "gpt-4o"))
        );
        MetricsQueryPort metrics = traceId -> java.util.Optional.empty();

        var snap = TraceDiffService.TraceSnapshot.fromSpans("trace-1", spans, metrics);
        assertThat(snap.tokensIn()).isNull();
        assertThat(snap.tokensOut()).isNull();
    }
}