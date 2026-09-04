package com.company.agentgateway.interfaces.webhook;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.shared.TenantId;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Webhook 订阅管理 + 死信查询（spec §25.3）。
 * POST /v1/admin/webhooks 订阅；DELETE 退订；GET 列表/死信。
 */
@RestController
@RequestMapping("/v1/admin/webhooks")
class AdminWebhookController {

    private final WebhookDispatcher dispatcher;
    private final AuditRepository auditRepository;

    AdminWebhookController(WebhookDispatcher dispatcher, AuditRepository auditRepository) {
        this.dispatcher = dispatcher;
        this.auditRepository = auditRepository;
    }

    @PostMapping
    public Map<String, Object> subscribe(@RequestBody SubscriptionDto dto) {
        dispatcher.subscribe(new WebhookDispatcher.Subscription(dto.url(), dto.secret(), dto.events()));
        auditRepository.append(new AuditRepository.AuditLog(
                UUID.randomUUID().toString(),
                new TenantId("default"),
                "admin",
                AuditRepository.AuditLog.ActorType.HUMAN,
                AuditRepository.AuditEventType.MODEL_CONFIG_UPDATE,
                Instant.now(),
                "webhook",
                dto.url(),
                "SUBSCRIBE",
                AuditRepository.AuditLog.Result.SUCCESS,
                null));
        return Map.of("subscribed", dto.url(), "events", dto.events());
    }

    @DeleteMapping
    public Map<String, Object> unsubscribe(@RequestParam String url) {
        dispatcher.unsubscribe(url);
        auditRepository.append(new AuditRepository.AuditLog(
                UUID.randomUUID().toString(),
                new TenantId("default"),
                "admin",
                AuditRepository.AuditLog.ActorType.HUMAN,
                AuditRepository.AuditEventType.MODEL_CONFIG_UPDATE,
                Instant.now(),
                "webhook",
                url,
                "UNSUBSCRIBE",
                AuditRepository.AuditLog.Result.SUCCESS,
                null));
        return Map.of("unsubscribed", url);
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return dispatcher.listSubscriptions().stream()
                .map(s -> Map.<String, Object>of("url", s.url(), "events", s.events()))
                .toList();
    }

    @GetMapping("/dead-letters")
    public List<Map<String, Object>> deadLetters() {
        return dispatcher.deadLetters().stream()
                .map(d -> Map.<String, Object>of("url", d.url(), "event", d.event(),
                        "attempts", d.attempts(), "error", d.lastError()))
                .toList();
    }

    /** POST /dead-letters/redeliver — 重新投递一条死信（运营#12）。 */
    @PostMapping("/dead-letters/redeliver")
    public Map<String, Object> redeliver(@RequestBody RedeliverDto dto) {
        var dl = dispatcher.deadLetters().stream()
                .filter(d -> d.url().equals(dto.url()) && d.event().equals(dto.event()))
                .findFirst().orElse(null);
        if (dl == null) {
            return Map.of("ok", false, "attempts", 0, "error", "dead letter not found");
        }
        boolean ok = dispatcher.redeliver(dl);
        return Map.of("ok", ok, "attempts", dl.attempts() + 1);
    }

    /** 订阅请求 DTO。 */
    public record SubscriptionDto(String url, String secret, List<String> events) {}

    /** 重新投递请求 DTO。 */
    public record RedeliverDto(String url, String event) {}
}
