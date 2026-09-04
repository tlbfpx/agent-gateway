package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.observability.SpanQueryRepository;
import com.company.agentgateway.domain.observability.SpanQueryRepository.TraceFilter;
import com.company.agentgateway.domain.observability.SpanQueryRepository.TraceSummary;
import com.company.agentgateway.domain.observability.SpanRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 调用链追踪查询端点(spec 2026-08-19 §5.3)。
 *
 * <p>瀑布图数据源:列表(过滤/分页)→ 详情(trace 全量 spans)。
 * 未装配 SpanQueryRepository(无 PG 配置)时返回 503 引导信息(§7 降级)。
 */
@RestController
@RequestMapping("/v1/admin/traces")
public class AdminTraceController {

    private final SpanQueryRepository spanQueryRepository;

    public AdminTraceController(@Autowired(required = false) SpanQueryRepository spanQueryRepository) {
        this.spanQueryRepository = spanQueryRepository;
    }

    /** 链路列表(瀑布图入口页)。 */
    @GetMapping
    public List<TraceSummary> list(
            @RequestParam(defaultValue = "1h") String range,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false, defaultValue = "false") boolean errorOnly,
            @RequestParam(required = false) Double minDurationMs,
            @RequestParam(required = false) String tenantId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        requireStorage();
        Instant from = Instant.now().minus(parseRange(range));
        TraceFilter filter = new TraceFilter(from, null, operation,
                errorOnly ? Boolean.TRUE : null, minDurationMs, tenantId);
        return spanQueryRepository.queryTraces(filter, Math.min(limit, 200), Math.max(offset, 0));
    }

    /** 单链路全量 spans(瀑布图详情)。 */
    @GetMapping("/{traceId}")
    public Map<String, Object> detail(@PathVariable String traceId) {
        requireStorage();
        List<SpanRecord> spans = spanQueryRepository.getSpans(traceId);
        if (spans.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "trace not found: " + traceId);
        }
        return Map.of("traceId", traceId, "spans", spans);
    }

    private void requireStorage() {
        if (spanQueryRepository == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "未配置持久化存储:请配置 observability.storage.jdbc-url 并启动 docker-compose.observability.yml");
        }
    }

    private static Duration parseRange(String range) {
        if (range.endsWith("m")) return Duration.ofMinutes(Math.max(1, Long.parseLong(range.substring(0, range.length() - 1))));
        if (range.endsWith("h")) return Duration.ofHours(Math.max(1, Long.parseLong(range.substring(0, range.length() - 1))));
        if (range.endsWith("d")) return Duration.ofDays(Math.max(1, Long.parseLong(range.substring(0, range.length() - 1))));
        return Duration.ofHours(1);
    }
}
