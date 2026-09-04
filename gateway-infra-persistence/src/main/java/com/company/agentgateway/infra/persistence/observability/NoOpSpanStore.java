package com.company.agentgateway.infra.persistence.observability;

import com.company.agentgateway.domain.observability.SpanQueryRepository;
import com.company.agentgateway.domain.observability.SpanRecord;

import java.util.List;

/**
 * NoOp SpanQueryRepository(Round 9 运营体验 + Round 8.5 Spring 4 修复):
 * 没有 PG 持久化时的 fallback bean;所有查询返回空列表。
 */
public class NoOpSpanStore implements SpanQueryRepository {

    @Override
    public List<TraceSummary> queryTraces(TraceFilter filter, int limit, int offset) {
        return List.of();
    }

    @Override
    public List<SpanRecord> getSpans(String traceId) {
        return List.of();
    }
}