package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.audit.AuditRepository.AuditEventType;
import com.company.agentgateway.domain.shared.TenantId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 限流实时配额（限流监控页 live 数据源）。
 *
 * <p>数据源为审计流：近 5 分钟流量折算 QPS / 并发，RATE_LIMIT_EXCEEDED
 * 事件折算 429 触发。契约对齐前端 ratelimit.ts 的 QuotaRow / RateLimitEvent。
 *
 * <p>端点（均需 X-API-Key）：
 * <ul>
 *   <li>GET /v1/admin/ratelimit/quotas?range=5m  — 5 维配额快照（tenant/user/agent/token-daily）</li>
 *   <li>GET /v1/admin/ratelimit/events?range=24h — 429 触发事件列表</li>
 * </ul>
 *
 * <p>限额为演示口径（与前端 LIMITS 同值）；网关限流配置中心落地后
 * 从配置读取，契约不变。
 */
@RestController
@RequestMapping("/v1/admin/ratelimit")
public class AdminRateLimitController {

    private final AuditRepository auditRepository;

    public AdminRateLimitController(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    /** 5 维配额快照：current 为 5m 窗口 QPS（agent 维度视作并发、token-daily 为 24h 估算）。 */
    @GetMapping("/quotas")
    public List<Map<String, Object>> quotas(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestParam(defaultValue = "5m") String range) {
        String tenant = resolveTenant(tenantId);
        Duration window = parseRange(range);
        Instant now = Instant.now();

        List<AuditRepository.AuditLog> recent = auditRepository.query(
                new TenantId(tenant), null, now.minus(window), null, 10_000);
        List<AuditRepository.AuditLog> exceeded = auditRepository.query(
                new TenantId(tenant), AuditEventType.RATE_LIMIT_EXCEEDED, now.minus(window), null, 10_000);
        List<AuditRepository.AuditLog> dayTokens = auditRepository.query(
                new TenantId(tenant), null, now.minus(Duration.ofHours(24)), null, 10_000);

        Map<String, Quota> byTenant = new LinkedHashMap<>();
        Map<String, Quota> byUser = new LinkedHashMap<>();
        Map<String, Quota> byAgent = new LinkedHashMap<>();

        for (AuditRepository.AuditLog l : recent) {
            byTenant.computeIfAbsent(tenant, k -> new Quota("tenant", tenant)).hit();
            byUser.computeIfAbsent(actor(l), k -> new Quota("user", actor(l))).hit();
            byAgent.computeIfAbsent(resource(l), k -> new Quota("agent", resource(l))).hit();
        }
        for (AuditRepository.AuditLog l : exceeded) {
            byUser.computeIfAbsent(actor(l), k -> new Quota("user", actor(l)))
                    .blocked(l.timestamp());
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        byTenant.values().forEach(q -> rows.add(q.toMap(window)));
        byUser.values().forEach(q -> rows.add(q.toMap(window)));
        byAgent.values().forEach(q -> rows.add(q.toMap(window)));
        // token-daily：单对象（当前租户），1500 token/次估算口径与 metrics/cost 一致
        long tokens24h = dayTokens.size() * 1500L;
        rows.add(new Quota("token-daily", tenant).tokens(tokens24h).toMap(window));
        return rows;
    }

    /** 429 触发事件（时间倒序）。 */
    @GetMapping("/events")
    public List<Map<String, Object>> events(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestParam(defaultValue = "24h") String range) {
        String tenant = resolveTenant(tenantId);
        Instant from = Instant.now().minus(parseRange(range));
        return auditRepository.query(new TenantId(tenant), AuditEventType.RATE_LIMIT_EXCEEDED, from, null, 500).stream()
                .sorted(Comparator.comparing(AuditRepository.AuditLog::timestamp).reversed())
                .map(l -> Map.<String, Object>of(
                        "id", l.eventId(),
                        "time", l.timestamp().toString(),
                        "dim", "user",
                        "id2", actor(l),
                        "name", actor(l),
                        "current", 0,
                        "limit", 10,
                        "reason", l.action() == null ? "rate limit exceeded" : l.action()))
                .toList();
    }

    /** 配额累计器。 */
    private static final class Quota {
        final String dim;
        final String id;
        long hits;
        long blocked;
        Instant lastBlockedAt;

        Quota(String dim, String id) {
            this.dim = dim;
            this.id = id;
        }

        Quota hit() {
            hits++;
            return this;
        }

        Quota blocked(Instant at) {
            blocked++;
            if (lastBlockedAt == null || at.isAfter(lastBlockedAt)) lastBlockedAt = at;
            return this;
        }

        Quota tokens(long t) {
            hits = t;
            return this;
        }

        Map<String, Object> toMap(Duration window) {
            long limit = switch (dim) {
                case "tenant" -> 100L;
                case "user" -> 10L;
                case "agent" -> 50L;
                default -> 1_000_000L;
            };
            // QPS：窗口内命中数 / 秒（agent 维度为并发计数，token-daily 直接用估算值）
            double current = switch (dim) {
                case "agent" -> hits;
                case "token-daily" -> hits;
                default -> Math.round(hits * 100.0 / Math.max(1, window.toSeconds())) / 100.0;
            };
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", id);
            m.put("dim", dim);
            m.put("current", current);
            m.put("limit", limit);
            m.put("blocked", blocked);
            if (lastBlockedAt != null) m.put("lastBlockedAt", lastBlockedAt.toString());
            return m;
        }
    }

    private static String actor(AuditRepository.AuditLog l) {
        return l.actor() == null ? "unknown" : l.actor();
    }

    private static String resource(AuditRepository.AuditLog l) {
        return l.resourceId() == null ? "unknown" : l.resourceId();
    }

    private static String resolveTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "primary" : tenantId;
    }

    private static Duration parseRange(String range) {
        if (range.endsWith("m")) return Duration.ofMinutes(Math.max(1, Long.parseLong(range.substring(0, range.length() - 1))));
        if (range.endsWith("h")) return Duration.ofHours(Math.max(1, Long.parseLong(range.substring(0, range.length() - 1))));
        if (range.endsWith("d")) return Duration.ofDays(Math.max(1, Long.parseLong(range.substring(0, range.length() - 1))));
        return Duration.ofMinutes(5);
    }
}
