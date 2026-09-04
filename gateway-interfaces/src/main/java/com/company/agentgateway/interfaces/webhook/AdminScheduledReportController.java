package com.company.agentgateway.interfaces.webhook;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 定时报表订阅管理（spec §25.4）。
 *
 * <ul>
 *   <li>GET    /v1/admin/reports/scheduled          — 列表（租户过滤 + 分页）</li>
 *   <li>POST   /v1/admin/reports/scheduled          — 创建订阅</li>
 *   <li>DELETE /v1/admin/reports/scheduled/{id}     — 取消订阅</li>
 *   <li>POST   /v1/admin/reports/scheduled/{id}/test — 立即触发一次（不改排期）</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin/reports/scheduled")
public class AdminScheduledReportController {

    private final ScheduledReportRepository repository;
    private final ScheduledReportScheduler scheduler;

    public AdminScheduledReportController(ScheduledReportRepository repository,
                                          ScheduledReportScheduler scheduler) {
        this.repository = repository;
        this.scheduler = scheduler;
    }

    /** 创建请求体。 */
    public record CreateRequest(String period, String range, String dim, String webhookUrl) {}

    @GetMapping
    public List<ScheduledReport> list(@RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                      @RequestParam(defaultValue = "0") int offset,
                                      @RequestParam(defaultValue = "50") int limit) {
        return repository.list(resolveTenant(tenantId), offset, limit);
    }

    @PostMapping
    public ResponseEntity<ScheduledReport> create(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestBody CreateRequest body) {
        ScheduledReport report;
        try {
            Instant now = Instant.now();
            report = new ScheduledReport(
                    UUID.randomUUID().toString(),
                    body.period(), body.range(), body.dim(), body.webhookUrl(),
                    resolveTenant(tenantId), now, now);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        // 首次触发定在一个周期后，避免创建瞬间立刻推送
        report = report.advanced();
        repository.save(report);
        return ResponseEntity.status(HttpStatus.CREATED).body(report);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable String id) {
        if (!repository.delete(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "scheduled report not found: " + id);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable String id) {
        if (!scheduler.fireNow(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "scheduled report not found: " + id);
        }
        return Map.of("triggered", true, "reportId", id);
    }

    private static String resolveTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "primary" : tenantId;
    }
}
