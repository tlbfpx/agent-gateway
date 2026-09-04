package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.observability.AlertStore;
import com.company.agentgateway.domain.observability.AlertStore.AlertRecord;
import com.company.agentgateway.domain.observability.AlertStore.AlertRule;
import com.company.agentgateway.domain.shared.TenantId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 告警中心(spec 2026-08-19 §5.4/§5.5):规则 CRUD + 告警流 + 认领/静默。
 *
 * <p>存储:AlertStore(PG 持久化,无 PG 时 503 引导)。
 * 求值由 AlertEngine(observability 模块)定时驱动,本控制器只读写。
 */
@RestController
@RequestMapping("/v1/admin/alerts")
public class AdminAlertController {

    private final AuditRepository auditRepository;
    private final AlertStore alertStore;

    public AdminAlertController(AuditRepository auditRepository,
                                @Autowired(required = false) AlertStore alertStore) {
        this.auditRepository = auditRepository;
        this.alertStore = alertStore;
    }

    // ================= rules CRUD =================

    @GetMapping("/rules")
    public List<AlertRule> rules(@RequestHeader("X-API-Key") String apiKey,
                                 @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        requireStorage();
        return alertStore.listRules(false);
    }

    @PostMapping("/rules")
    public AlertRule create(@RequestHeader("X-API-Key") String apiKey,
                            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                            @RequestBody RuleDto body) {
        requireStorage();
        AlertRule rule = new AlertRule(
                null, requireName(body), body.metricName(),
                parseOperator(body.operator()),
                body.threshold() == null ? 0.0 : body.threshold(),
                body.windowSeconds() == null || body.windowSeconds() <= 0 ? 300 : body.windowSeconds(),
                body.silenceMinutes() == null || body.silenceMinutes() <= 0 ? 30 : body.silenceMinutes(),
                body.dedupKeyTpl() == null || body.dedupKeyTpl().isBlank() ? "{rule}:{metric}" : body.dedupKeyTpl(),
                body.severity() == null || body.severity().isBlank() ? "warning" : body.severity(),
                body.enabled() == null || body.enabled(),
                Instant.now(), Instant.now());
        AlertRule saved = alertStore.saveRule(rule);
        appendAudit(tenantId, "alert-rule-create", saved.id(), AuditRepository.AuditLog.Result.SUCCESS);
        return saved;
    }

    @PutMapping("/rules/{id}")
    public AlertRule update(@RequestHeader("X-API-Key") String apiKey,
                            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                            @PathVariable String id,
                            @RequestBody RuleDto body) {
        requireStorage();
        AlertRule existing = alertStore.getRule(id)
                .orElseThrow(() -> notFound(id));
        AlertRule updated = new AlertRule(
                id,
                body.name() == null || body.name().isBlank() ? existing.name() : body.name(),
                body.metricName() == null ? existing.metricName() : body.metricName(),
                body.operator() == null ? existing.operator() : parseOperator(body.operator()),
                body.threshold() == null || body.threshold() <= 0 ? existing.threshold() : body.threshold(),
                body.windowSeconds() == null || body.windowSeconds() <= 0 ? existing.windowSeconds() : body.windowSeconds(),
                body.silenceMinutes() == null || body.silenceMinutes() <= 0 ? existing.silenceMinutes() : body.silenceMinutes(),
                body.dedupKeyTpl() == null ? existing.dedupKeyTpl() : body.dedupKeyTpl(),
                body.severity() == null ? existing.severity() : body.severity(),
                body.enabled() == null ? existing.enabled() : body.enabled(),
                existing.createdAt(), Instant.now());
        AlertRule saved = alertStore.saveRule(updated);
        appendAudit(tenantId, "alert-rule-update", id, AuditRepository.AuditLog.Result.SUCCESS);
        return saved;
    }

    @DeleteMapping("/rules/{id}")
    public Map<String, Object> delete(@RequestHeader("X-API-Key") String apiKey,
                                      @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                      @PathVariable String id) {
        requireStorage();
        if (!alertStore.deleteRule(id)) throw notFound(id);
        appendAudit(tenantId, "alert-rule-delete", id, AuditRepository.AuditLog.Result.SUCCESS);
        return Map.of("deleted", id);
    }

    // ================= 告警流 =================

    /** 告警流(firing 优先,时间倒序)。 */
    @GetMapping
    public List<AlertRecord> alerts(@RequestHeader("X-API-Key") String apiKey,
                                    @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                    @RequestParam(required = false) String state,
                                    @RequestParam(required = false) String severity,
                                    @RequestParam(defaultValue = "100") int limit) {
        requireStorage();
        return alertStore.queryAlerts(state, severity, Math.min(Math.max(limit, 1), 500));
    }

    /** 认领(记录认领人+备注)。 */
    @PostMapping("/{id}/ack")
    public AlertRecord ack(@RequestHeader("X-API-Key") String apiKey,
                           @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                           @PathVariable String id,
                           @RequestBody(required = false) AckDto body) {
        requireStorage();
        AlertRecord r = alertStore.get(id).orElseThrow(() -> notFound(id));
        return alertStore.update(new AlertRecord(r.id(), r.ruleId(), r.severity(), r.state(),
                r.dedupKey(), r.labels(), r.firstFiredAt(), r.recentlyTriggeredAt(),
                r.triggerCount(), r.observedValue(), r.threshold(),
                body == null || body.claimedBy() == null || body.claimedBy().isBlank() ? "admin" : body.claimedBy(),
                body == null ? r.note() : body.note(),
                r.resolvedAt()));
    }

    /** 手动静默(状态不变,仅记录;自动静默由引擎的 silence 窗口控制)。 */
    @PostMapping("/{id}/silence")
    public AlertRecord silence(@RequestHeader("X-API-Key") String apiKey,
                               @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                               @PathVariable String id,
                               @RequestBody(required = false) AckDto body) {
        requireStorage();
        AlertRecord r = alertStore.get(id).orElseThrow(() -> notFound(id));
        String note = (r.note() == null ? "" : r.note() + " | ") + "silenced"
                + (body != null && body.note() != null ? ": " + body.note() : "");
        return alertStore.update(new AlertRecord(r.id(), r.ruleId(), r.severity(), r.state(),
                r.dedupKey(), r.labels(), r.firstFiredAt(), r.recentlyTriggeredAt(),
                r.triggerCount(), r.observedValue(), r.threshold(),
                r.claimedBy(), note, r.resolvedAt()));
    }

    // ================= 兼容旧前端契约(events 派生视图) =================

    /** 旧 events 端点保留:rate_limit 从审计派生(前端 alerts.ts 迁移后移除)。 */
    @GetMapping("/events")
    public List<Map<String, Object>> events(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestParam(defaultValue = "24h") String range) {
        String tenant = resolveTenant(tenantId);
        Instant from = Instant.now().minus(parseRange(range));
        return auditRepository.query(
                        new TenantId(tenant), AuditRepository.AuditEventType.RATE_LIMIT_EXCEEDED, from, null, 500)
                .stream()
                .map(l -> Map.<String, Object>of(
                        "id", l.eventId(),
                        "ruleId", "rate-limit",
                        "ruleName", "限流触发",
                        "severity", "warning",
                        "metric", "rate_limit_hit",
                        "value", 0,
                        "threshold", 0,
                        "time", l.timestamp().toString(),
                        "message", l.errorMessage() == null ? "rate limit exceeded" : l.errorMessage(),
                        "acknowledged", false))
                .toList();
    }

    // ================= helpers =================

    private void requireStorage() {
        if (alertStore == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "未配置持久化存储:请配置 observability.storage.jdbc-url 并启动 docker-compose.observability.yml");
        }
    }

    private static String requireName(RuleDto body) {
        if (body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rule name required");
        }
        return body.name();
    }

    private static AlertRule.Operator parseOperator(String op) {
        if (op == null) return AlertRule.Operator.GT;
        try {
            return AlertRule.Operator.valueOf(op.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid operator: " + op);
        }
    }

    private void appendAudit(String tenantId, String action, String resource, AuditRepository.AuditLog.Result result) {
        auditRepository.append(new AuditRepository.AuditLog(
                "al-" + System.nanoTime(), new TenantId(resolveTenant(tenantId)), "admin",
                AuditRepository.AuditLog.ActorType.HUMAN, AuditRepository.AuditEventType.MODEL_CONFIG_UPDATE,
                Instant.now(), "alert-rule", resource, action, result, null));
    }

    private static ResponseStatusException notFound(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "alert not found: " + id);
    }

    private static String resolveTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "primary" : tenantId;
    }

    private static Duration parseRange(String range) {
        if (range.endsWith("h")) return Duration.ofHours(Math.max(1, Long.parseLong(range.substring(0, range.length() - 1))));
        if (range.endsWith("d")) return Duration.ofDays(Math.max(1, Long.parseLong(range.substring(0, range.length() - 1))));
        return Duration.ofHours(24);
    }

    /** 规则请求体(数值用包装类型:部分更新时字段可缺省)。 */
    public record RuleDto(String name, String metricName, String operator, Double threshold,
                          Integer windowSeconds, Integer silenceMinutes, String dedupKeyTpl,
                          String severity, Boolean enabled) {}

    /** 认领/静默请求体。 */
    public record AckDto(String claimedBy, String note) {}
}
