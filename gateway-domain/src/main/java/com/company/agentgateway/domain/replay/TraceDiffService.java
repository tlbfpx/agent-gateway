package com.company.agentgateway.domain.replay;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trace Diff 引擎(Sprint 2 P0 §3.4):
 * 比对两条 trace 的 5 维度差异(tokens / latency / model / message_count / response_similarity)。
 *
 * <h2>用途</h2>
 * <ul>
 *   <li>What-if replay vs 原始 trace → "切到 gpt-4o 实际差多少"</li>
 *   <li>批量变体横向对比 → "A/B 测试结论"</li>
 * </ul>
 *
 * <h2>实现</h2>
 * <p>纯函数,无依赖,便于单元测试。
 * <p>响应文本相似度使用 Levenshtein 距离归一化(0-1);同模型同 prompt 期望 1.0。
 */
public final class TraceDiffService {

    private TraceDiffService() {}

    /** 单维度差异 */
    public record FieldDiff(String field, Object from, Object to, String verdict) {}

    /** 完整 diff 结果 */
    public record DiffResult(
            String fromTraceId,
            String toTraceId,
            List<FieldDiff> fieldDiffs,
            double responseSimilarity,
            Set<String> toolSetAdded,
            Set<String> toolSetRemoved,
            int messageCountDelta,
            String verdict
    ) {}

    public static DiffResult diff(TraceSnapshot a, TraceSnapshot b) {
        List<FieldDiff> diffs = new java.util.ArrayList<>();
        diffs.add(verdict("tokensIn", a.tokensIn(), b.tokensIn()));
        diffs.add(verdict("tokensOut", a.tokensOut(), b.tokensOut()));
        diffs.add(verdict("latencyMs", a.latencyMs(), b.latencyMs()));
        diffs.add(verdict("model", a.model(), b.model()));
        diffs.add(verdict("messageCount", a.messageCount(), b.messageCount()));

        double sim = similarity(a.responseText(), b.responseText());

        Set<String> toolsA = a.tools() == null ? Set.of() : new HashSet<>(a.tools());
        Set<String> toolsB = b.tools() == null ? Set.of() : new HashSet<>(b.tools());
        Set<String> added = new HashSet<>(toolsB);
        added.removeAll(toolsA);
        Set<String> removed = new HashSet<>(toolsA);
        removed.removeAll(toolsB);

        int messageDelta = b.messageCount() - a.messageCount();
        String verdict = computeVerdict(sim, messageDelta, toolsA, toolsB);

        return new DiffResult(
                a.traceId(), b.traceId(),
                diffs, sim, added, removed, messageDelta, verdict);
    }

    private static FieldDiff verdict(String field, Object from, Object to) {
        String v = (from == null ? "<null>" : from.toString())
                .equals(to == null ? "<null>" : to.toString()) ? "same" : "diff";
        return new FieldDiff(field, from, to, v);
    }

    /** Levenshtein 归一化相似度(0-1)。 */
    public static double similarity(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;
        int dist = levenshtein(a, b);
        return 1.0 - (double) dist / maxLen;
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[b.length()];
    }

    private static String computeVerdict(double sim, int messageDelta,
                                          Set<String> toolsA, Set<String> toolsB) {
        if (sim >= 0.99 && messageDelta == 0 && toolsA.equals(toolsB)) return "identical";
        if (sim >= 0.90) return "near_identical";
        if (sim >= 0.70) return "similar";
        if (sim >= 0.40) return "different";
        return "very_different";
    }

    /** Replay 双方输入快照(可来自 span attributes 或 payload 还原)。 */
    public record TraceSnapshot(
            String traceId,
            String model,
            Integer tokensIn,
            Integer tokensOut,
            Long latencyMs,
            int messageCount,
            List<String> tools,
            String responseText
    ) {
        public TraceSnapshot {
            if (tools == null) tools = List.of();
            if (responseText == null) responseText = "";
        }

        /**
         * Sprint 2 P4:从 SpanRecord.attributes 反向合成快照(payload 缺失时回退)。
         *
         * <p>解析约定:
         * <ul>
         *   <li>model:从 attributes.model 取(llm.call span)</li>
         *   <li>tokensIn/Out:从 llm.call span attributes 或 metrics(此处简化,从 attributes 取)</li>
         *   <li>latencyMs:从最大 span.endTime - 最小 span.startTime 计算</li>
         *   <li>tools:从 agent.call span.name 收集</li>
         *   <li>messageCount:1(简化 — 实际从 prompt 解析;此处占位)</li>
         *   <li>responseText:从 attributes.response 或 name='gateway.chat' 的 span 取</li>
         * </ul>
         */
        public static TraceSnapshot fromSpans(String traceId, List<SpanView> spans) {
            return fromSpans(traceId, spans, null);
        }

        /**
         * Sprint 2 P2.4 增强版:额外传入 {@link MetricsQueryPort},spans 缺 token 时回退 metrics。
         */
        public static TraceSnapshot fromSpans(String traceId, List<SpanView> spans,
                                              MetricsQueryPort metricsPort) {
            String model = null;
            Integer tokensIn = null;
            Integer tokensOut = null;
            long minStart = Long.MAX_VALUE;
            long maxEnd = Long.MIN_VALUE;
            java.util.List<String> tools = new java.util.ArrayList<>();
            String responseText = "";
            for (SpanView s : spans) {
                if (s.startTime() != null) minStart = Math.min(minStart, s.startTime());
                if (s.endTime() != null) maxEnd = Math.max(maxEnd, s.endTime());
                if (s.name() != null && s.name().startsWith("agent.call")) {
                    String a = s.attributes() == null ? null : s.attributes().get("agent_name");
                    if (a != null && !tools.contains(a)) tools.add(a);
                }
                if (s.name() != null && s.name().startsWith("llm.call")) {
                    if (model == null && s.attributes() != null) {
                        model = s.attributes().get("model");
                    }
                    if (s.attributes() != null) {
                        String tin = s.attributes().get("tokens_in");
                        if (tin != null) tokensIn = safeParseInt(tin, tokensIn);
                        String tout = s.attributes().get("tokens_out");
                        if (tout != null) tokensOut = safeParseInt(tout, tokensOut);
                    }
                }
                if (s.name() != null && s.name().startsWith("gateway.chat") && s.attributes() != null) {
                    String resp = s.attributes().get("response");
                    if (resp != null) responseText = resp;
                }
            }

            // P2.4:spans 缺 token 时回退 metrics
            if ((tokensIn == null || tokensOut == null) && metricsPort != null) {
                MetricsQueryPort.Tokens mt = metricsPort.findTokensForTrace(traceId).orElse(null);
                if (mt != null) {
                    if (tokensIn == null && mt.tokensIn() > 0) tokensIn = mt.tokensIn();
                    if (tokensOut == null && mt.tokensOut() > 0) tokensOut = mt.tokensOut();
                }
            }

            long latency = (minStart == Long.MAX_VALUE || maxEnd == Long.MIN_VALUE)
                    ? 0L : Math.max(0, maxEnd - minStart);
            return new TraceSnapshot(traceId, model, tokensIn, tokensOut, latency,
                    1, tools, responseText);
        }

        private static Integer safeParseInt(String s, Integer fallback) {
            try { return Integer.parseInt(s); } catch (Exception e) { return fallback; }
        }
    }

    /**
     * Span 最小视图(Sprint 2 P4):仅用于 snapshot 合成,与 infra-persistence 的 SpanRecord 解耦。
     * 调用方在 AdminReplayController 把 SpanRecord → SpanView 映射后传入。
     */
    public record SpanView(String name, Long startTime, Long endTime,
                            Map<String, String> attributes) {}
}