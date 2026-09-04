package com.company.agentgateway.infra.persistence.observability;

import com.company.agentgateway.domain.observability.SpanRecord;

import java.util.List;

/**
 * Span 写入端口(供 observability 模块的 exporter 回调,避免其直接依赖 JDBC)。
 */
public interface SpanWriter {
    int batchInsert(List<SpanRecord> spans);
}
