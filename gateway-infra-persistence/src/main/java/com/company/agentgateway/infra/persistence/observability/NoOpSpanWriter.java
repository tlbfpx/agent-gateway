package com.company.agentgateway.infra.persistence.observability;

import com.company.agentgateway.domain.observability.SpanRecord;

import java.util.List;

/**
 * NoOp SpanWriter(Round 9 运营体验):
 * 没有 PG 持久化时的 fallback bean;批量插入返回 0。
 */
public class NoOpSpanWriter implements SpanWriter {

    @Override
    public int batchInsert(List<SpanRecord> spans) {
        return 0;
    }
}