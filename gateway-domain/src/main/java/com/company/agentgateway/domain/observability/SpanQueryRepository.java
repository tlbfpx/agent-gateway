package com.company.agentgateway.domain.observability;

import java.time.Instant;
import java.util.List;

/**
 * 出站端口:调用链查询(spec 2026-08-19 §5.3)。由 infra-persistence 实现(PgSpanStore)。
 */
public interface SpanQueryRepository {

    /**
     * 链路列表查询(瀑布图入口页)。
     *
     * @param filter 过滤条件(time range / operation / status / minDuration / tenant)
     * @param limit  分页大小
     * @param offset 分页偏移
     */
    List<TraceSummary> queryTraces(TraceFilter filter, int limit, int offset);

    /** 单链路全量 spans(瀑布图数据源);不存在返回空列表。 */
    List<SpanRecord> getSpans(String traceId);

    /**
     * 链路摘要(列表页行模型)。
     *
     * @param agentNames 链路涉及的 A2A Agent 名(agent.call span 的 agent_name 去重)
     */
    record TraceSummary(String traceId, String rootSpanName, Instant startTime,
                        double totalDurationMs, int spanCount, int errorCount,
                        List<String> agentNames) {}

    /** 列表过滤条件;null 字段表示不过滤。 */
    record TraceFilter(Instant from, Instant to, String operation,
                       Boolean errorOnly, Double minDurationMs, String tenantId) {}
}
