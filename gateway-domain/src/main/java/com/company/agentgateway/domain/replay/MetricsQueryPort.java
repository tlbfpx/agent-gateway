package com.company.agentgateway.domain.replay;

import java.util.Optional;

/**
 * 指标查询端口(Sprint 2 P2.4):补全 SpanSnapshot 缺失字段。
 *
 * <p>SpanRecord.attributes 经常不携带 tokens_in/out 字段(只在 llm.call span 上偶尔有);
 * 准确值在 metrics_samples 表(metric_name='llm_tokens_in/out')。
 * 本端口让 fromSpans 在 spans 缺 token 时,再走 metrics 补全。
 */
public interface MetricsQueryPort {

    /**
     * 查询某 trace 的 LLM token 统计。
     *
     * @param traceId traceId
     * @return Tokens(可能为空 if metrics 也不存在)
     */
    Optional<Tokens> findTokensForTrace(String traceId);

    /** token 统计聚合。 */
    record Tokens(int tokensIn, int tokensOut) {
        public static Tokens empty() {
            return new Tokens(0, 0);
        }
        public boolean isEmpty() {
            return tokensIn == 0 && tokensOut == 0;
        }
    }
}