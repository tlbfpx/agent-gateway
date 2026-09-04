package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.audit.AuditRepository.AuditEventType;
import com.company.agentgateway.domain.audit.AuditRepository.AuditLog;
import com.company.agentgateway.domain.audit.AuditRepository.AuditQuery;
import com.company.agentgateway.domain.shared.TenantId;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/** 审计日志查询（spec §22.6 管理端）。GET /v1/admin/audit/logs */
@RestController
@RequestMapping("/v1/admin/audit")
public class AdminAuditController {

    /** 单次导出上限（spec §22.6 SOC2 要求：导出完整日志便于审计员核查） */
    private static final int EXPORT_LIMIT_MAX = 100_000;

    private final AuditRepository auditRepository;

    public AdminAuditController(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @GetMapping("/logs")
    public List<Map<String, Object>> logs(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestParam(defaultValue = "au") String tenant,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        AuditEventType eventType = type == null || type.isBlank() ? null
                : parseEnum(type, "type", AuditEventType.values());
        Instant fromI = parseInstant(from, "from");
        Instant toI = parseInstant(to, "to");
        AuditLog.Result resultFilter = result == null || result.isBlank() ? null
                : parseEnum(result, "result", AuditLog.Result.values());
        if (limit < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be >= 0");
        }
        if (offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "offset must be >= 0");
        }
        AuditQuery query = new AuditQuery(new TenantId(tenant), eventType, fromI, toI,
                resultFilter, keyword, limit == 0 ? 50 : limit, offset);
        return auditRepository.query(query).stream()
                .map(l -> Map.<String, Object>of(
                        "eventId", l.eventId(),
                        "actor", l.actor(),
                        "type", l.eventType().name(),
                        "time", l.timestamp().toString(),
                        "resource", l.resourceId(),
                        "action", l.action(),
                        "result", l.result().name(),
                        "detail", l.errorMessage() == null ? "" : l.errorMessage()))
                .toList();
    }

    /**
     * 导出审计日志为 CSV（spec §22.6 SOC2/ISO27001 合规）。
     *
     * <p>{@code GET /v1/admin/audit/logs/export.csv?tenant=&type=&from=&to=&result=&keyword=&limit=}
     * - limit 默认 10000，上限 {@value #EXPORT_LIMIT_MAX}（避免内存炸）
     * - Content-Type: text/csv; charset=utf-8
     * - Content-Disposition: attachment; filename="audit-{tenant}-{ISO}.csv"
     * - 表头顺序固定：eventId, tenant, actor, actorType, type, time, resourceType, resourceId, action, result, detail
     */
    @GetMapping(value = "/logs/export.csv", produces = "text/csv;charset=UTF-8")
    public void exportCsv(
            HttpServletResponse resp,
            @RequestParam(defaultValue = "au") String tenant,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "10000") int limit) throws IOException {

        int exportLimit = Math.min(Math.max(limit, 1), EXPORT_LIMIT_MAX);
        AuditEventType eventType = type == null || type.isBlank() ? null
                : parseEnum(type, "type", AuditEventType.values());
        Instant fromI = parseInstant(from, "from");
        Instant toI = parseInstant(to, "to");
        AuditLog.Result resultFilter = result == null || result.isBlank() ? null
                : parseEnum(result, "result", AuditLog.Result.values());

        AuditQuery query = new AuditQuery(new TenantId(tenant), eventType, fromI, toI,
                resultFilter, keyword, exportLimit, 0);
        List<AuditLog> rows = auditRepository.query(query);

        // BOM 让 Excel 识别 UTF-8（spec §22.6 合规审计员多在 Excel 看）
        resp.setStatus(HttpStatus.OK.value());
        resp.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8").toString());
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"audit-" + safeFilename(tenant) + "-"
                        + Instant.now().toString().replace(':', '-') + ".csv\"");

        try (PrintWriter w = resp.getWriter()) {
            w.write("﻿"); // UTF-8 BOM
            // 表头
            w.write("eventId,tenant,actor,actorType,type,time,resourceType,resourceId,action,result,detail\n");
            for (AuditLog l : rows) {
                w.write(csv(l.eventId())); w.write(',');
                w.write(csv(l.tenant().value())); w.write(',');
                w.write(csv(l.actor())); w.write(',');
                w.write(csv(l.actorType().name())); w.write(',');
                w.write(csv(l.eventType().name())); w.write(',');
                w.write(csv(l.timestamp().toString())); w.write(',');
                w.write(csv(l.resourceType())); w.write(',');
                w.write(csv(l.resourceId())); w.write(',');
                w.write(csv(l.action())); w.write(',');
                w.write(csv(l.result().name())); w.write(',');
                w.write(csv(l.errorMessage() == null ? "" : l.errorMessage()));
                w.write('\n');
            }
            w.flush();
        }
    }

    /** RFC 4180：双引号包裹 + 内部双引号转义。 */
    private static String csv(String value) {
        if (value == null) return "";
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String safeFilename(String tenant) {
        return tenant.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static <E extends Enum<E>> E parseEnum(String value, String name, E[] values) {
        for (E v : values) {
            if (v.name().equalsIgnoreCase(value.trim())) {
                return v;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid " + name + ": " + value);
    }

    private static Instant parseInstant(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid " + name + ": " + value);
        }
    }
}
