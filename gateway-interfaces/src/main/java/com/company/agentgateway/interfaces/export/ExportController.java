package com.company.agentgateway.interfaces.export;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.billing.BillingPort;
import com.company.agentgateway.domain.billing.UsageQuery;
import com.company.agentgateway.domain.billing.UsageRecord;
import com.company.agentgateway.domain.observability.SpanQueryRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 运营数据导出端点(Round 9):
 * - /v1/admin/audit/export
 * - /v1/admin/billing/export
 * - /v1/admin/traces/export
 *
 * <p>支持 format=xlsx(默认)|parquet(P3 后续)。
 * 时间范围 from/to 默认最近 7 天。
 */
@RestController
@RequestMapping("/v1/admin")
public class ExportController {

    private final AuditRepository auditRepository;
    private final BillingPort billingPort;
    private final SpanQueryRepository spanQuery;

    public ExportController(AuditRepository auditRepository, BillingPort billingPort, SpanQueryRepository spanQuery) {
        this.auditRepository = auditRepository;
        this.billingPort = billingPort;
        this.spanQuery = spanQuery;
    }

    @GetMapping("/audit/export")
    public ResponseEntity<byte[]> exportAudit(
            @RequestParam(value = "format", required = false) String format,
            @RequestParam(value = "from", required = false) Long from,
            @RequestParam(value = "to", required = false) Long to) {
        ExportFormat fmt = ExportFormat.parse(format);
        TimeRange range = TimeRange.of(from, to);

        var query = new AuditRepository.AuditQuery(
                null, null, range.from, range.to, null, null, 100_000, 0);
        List<AuditRepository.AuditLog> entries = auditRepository.query(query);

        List<String> columns = List.of("tenant", "actor", "eventType", "result", "errorMessage", "timestamp");
        List<List<Object>> rows = new ArrayList<>(entries.size());
        for (var e : entries) {
            rows.add(List.of(
                    e.tenant() != null ? e.tenant().value() : null,
                    e.actor(),
                    e.eventType().name(),
                    e.result().name(),
                    e.errorMessage() != null ? e.errorMessage() : "",
                    e.timestamp().toString()
            ));
        }
        return buildResponse(fmt, "audit", columns, rows);
    }

    @GetMapping("/billing/export")
    public ResponseEntity<byte[]> exportBilling(
            @RequestParam(value = "format", required = false) String format,
            @RequestParam(value = "from", required = false) Long from,
            @RequestParam(value = "to", required = false) Long to) {
        ExportFormat fmt = ExportFormat.parse(format);
        TimeRange range = TimeRange.of(from, to);

        // UsageQuery: tenant=null = all tenants(运营视角)
        UsageQuery query = new UsageQuery(null, range.from, range.to, null, null);
        List<UsageRecord> records = billingPort.queryUsage(query);

        List<String> columns = List.of("tenant", "user", "model", "agentName", "tokensIn", "tokensOut", "cost", "timestamp");
        List<List<Object>> rows = new ArrayList<>(records.size());
        for (var r : records) {
            rows.add(List.of(
                    r.tenant() != null ? r.tenant().value() : null,
                    r.user() != null ? r.user().value() : null,
                    r.model() != null ? r.model().value() : null,
                    r.agentName() != null ? r.agentName() : null,
                    r.tokensIn(),
                    r.tokensOut(),
                    r.cost() != null ? r.cost().toPlainString() : null,
                    r.timestamp().toString()
            ));
        }
        return buildResponse(fmt, "billing", columns, rows);
    }

    @GetMapping("/traces/export")
    public ResponseEntity<byte[]> exportTraces(
            @RequestParam(value = "format", required = false) String format,
            @RequestParam(value = "from", required = false) Long from,
            @RequestParam(value = "to", required = false) Long to) {
        ExportFormat fmt = ExportFormat.parse(format);
        TimeRange range = TimeRange.of(from, to);

        var filter = new SpanQueryRepository.TraceFilter(range.from, range.to,
                null, null, null, null);
        var traces = spanQuery.queryTraces(filter, 100_000, 0);

        List<String> columns = List.of("traceId", "rootSpanName", "startTime", "totalDurationMs", "spanCount", "errorCount", "agentNames");
        List<List<Object>> rows = new ArrayList<>(traces.size());
        for (var t : traces) {
            rows.add(List.of(
                    t.traceId(),
                    t.rootSpanName(),
                    t.startTime() != null ? t.startTime().toString() : null,
                    t.totalDurationMs(),
                    t.spanCount(),
                    t.errorCount(),
                    String.join(",", t.agentNames() != null ? t.agentNames() : List.of())
            ));
        }
        return buildResponse(fmt, "traces", columns, rows);
    }

    private static ResponseEntity<byte[]> buildResponse(ExportFormat fmt, String fileNamePrefix,
                                                         List<String> columns, List<List<Object>> rows) {
        byte[] data = XlsxExporter.export(fileNamePrefix, columns, rows);
        String filename = fileNamePrefix + "-" + Instant.now().getEpochSecond() + fmt.extension;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(fmt.contentType));
        headers.setContentDispositionFormData("attachment", filename);
        return new ResponseEntity<>(data, headers, 200);
    }

    /** 时间范围(from/to 缺失默认最近 7 天)。 */
    record TimeRange(Instant from, Instant to) {
        static TimeRange of(Long fromMs, Long toMs) {
            Instant now = Instant.now();
            Instant to = toMs != null ? Instant.ofEpochMilli(toMs) : now;
            Instant from = fromMs != null ? Instant.ofEpochMilli(fromMs) : to.minus(7, ChronoUnit.DAYS);
            return new TimeRange(from, to);
        }
    }
}