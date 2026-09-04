package com.company.agentgateway.domain.observability;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 调用链 span 记录(spec 2026-08-19 §4.1)。
 *
 * <p>领域层的中立数据载体:OTel SDK 的 span 经导出器适配为此记录后落库,
 * 查询侧(瀑布图)也用它呈现 —— 领域不依赖 OTel API。
 */
public record SpanRecord(
        String traceId,
        String spanId,
        String parentSpanId,
        String name,
        Kind kind,
        Instant startTime,
        Instant endTime,
        Double durationMs,
        Status status,
        Map<String, String> attributes,
        List<SpanEvent> events) {

    public enum Kind { SERVER, CLIENT, INTERNAL }

    public enum Status { OK, ERROR }

    /** span 内事件(异常、重试等时间点标注)。 */
    public record SpanEvent(Instant time, String name, Map<String, String> attributes) {}
}
