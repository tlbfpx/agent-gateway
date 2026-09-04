package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.audit.AuditRepository.AuditEventType;
import com.company.agentgateway.domain.audit.AuditRepository.AuditLog;
import com.company.agentgateway.domain.audit.AuditRepository.AuditQuery;
import com.company.agentgateway.domain.shared.TenantId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/** 审计日志查询（spec §22.6 管理端）。GET /v1/admin/audit/logs */
@RestController
@RequestMapping("/v1/admin/audit")
public class AdminAuditController {

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
