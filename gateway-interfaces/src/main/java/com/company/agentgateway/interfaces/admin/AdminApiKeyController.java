package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.iam.AgentGrant;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import com.company.agentgateway.infra.security.ApiKeyStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * API Key 管理端点（spec §18）。
 * <ul>
 *   <li>POST /v1/admin/api-keys：签发（返回完整 key，仅显示一次）</li>
 *   <li>GET /v1/admin/api-keys：列表</li>
 *   <li>DELETE /v1/admin/api-keys/{key}：吊销</li>
 * </ul>
 * 一期：直接操作 InMemoryApiKeyStore（管理 token 简化为 admin 访问）。
 */
@RestController
@RequestMapping("/v1/admin/api-keys")
public class AdminApiKeyController {

    private final ApiKeyStore apiKeyStore;
    private final AuditRepository auditRepository;

    public AdminApiKeyController(ApiKeyStore apiKeyStore, AuditRepository auditRepository) {
        this.apiKeyStore = apiKeyStore;
        this.auditRepository = auditRepository;
    }

    /** 列表（key 脱敏显示：sk-****abcd）。 */
    @GetMapping
    public java.util.List<java.util.Map<String, Object>> list(
            @RequestHeader("X-API-Key") String apiKey) {
        return apiKeyStore.entries().stream()
                .map(e -> {
                    ApiKeyStore.ApiKeyBinding b = e.getValue();
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    String k = e.getKey();
                    m.put("id", k.length() > 8 ? k.substring(0, 5) + "****" + k.substring(k.length() - 4) : k);
                    m.put("owner", b.user().value());
                    m.put("tenant", b.tenant().value());
                    m.put("enabled", !b.revoked());
                    m.put("models", b.allowedModels().stream().map(ModelId::value).toList());
                    m.put("agents", b.agentGrants().stream().map(com.company.agentgateway.domain.iam.AgentGrant::agentName).toList());
                    if (b.expiresAt() != null) {
                        m.put("expiresAt", b.expiresAt().toString());
                        m.put("expired", b.isExpired(java.time.Instant.now()));
                    }
                    return m;
                })
                .toList();
    }

    /** 签发 API Key（绑定 tenant/user/grants/models）。完整 key 仅此次返回。 */
    @PostMapping
    public Map<String, Object> create(@RequestBody CreateApiKeyRequest req) {
        // 输入校验：tenant/user 必填，缺失时返回 400 而非值对象构造抛 IllegalArgumentException 导致 500
        if (req == null || isBlank(req.tenant()) || isBlank(req.user())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenant and user are required");
        }
        String apiKey = "sk-" + UUID.randomUUID().toString().replace("-", "");
        var binding = new ApiKeyStore.ApiKeyBinding(
                new TenantId(req.tenant()),
                new UserId(req.user()),
                req.agentGrants() == null ? Set.of() :
                        req.agentGrants().stream().map(a -> new AgentGrant(a, Set.of())).collect(java.util.stream.Collectors.toSet()),
                req.allowedModels() == null ? Set.of() :
                        req.allowedModels().stream().map(ModelId::new).collect(java.util.stream.Collectors.toSet()),
                false,
                java.util.Set.of(new TenantId(req.tenant())),
                parseExpiresAt(req.expiresAt()));
        if (apiKeyStore instanceof com.company.agentgateway.infra.security.JsonFileApiKeyStore fileStore) {
            fileStore.register(apiKey, binding); // 持久化到 data/api-keys.json
        }
        auditRepository.append(new AuditRepository.AuditLog(
                UUID.randomUUID().toString(),
                new TenantId(req.tenant()),
                "admin",
                AuditRepository.AuditLog.ActorType.HUMAN,
                AuditRepository.AuditEventType.API_KEY_CREATE,
                Instant.now(),
                "api-key",
                req.user(),
                "CREATE",
                AuditRepository.AuditLog.Result.SUCCESS,
                null));
        return Map.of("apiKey", apiKey, "tenant", req.tenant(), "user", req.user(),
                "note", "完整 key 仅此次显示，请妥善保存");
    }

    /** 吊销 API Key。 */
    @DeleteMapping("/{key}")
    public Map<String, Object> revoke(@PathVariable String key) {
        if (apiKeyStore instanceof com.company.agentgateway.infra.security.JsonFileApiKeyStore fileStore) {
            fileStore.revoke(key);
        }
        auditRepository.append(new AuditRepository.AuditLog(
                UUID.randomUUID().toString(),
                new TenantId("default"),
                "admin",
                AuditRepository.AuditLog.ActorType.HUMAN,
                AuditRepository.AuditEventType.API_KEY_DELETE,
                Instant.now(),
                "api-key",
                key,
                "REVOKE",
                AuditRepository.AuditLog.Result.SUCCESS,
                null));
        return Map.of("revoked", key);
    }

    /** 请求 DTO。expiresAt：ISO-8601（2026-12-31T23:59:59Z）或日期（2026-12-31，按当日 UTC 末）；null/空 = 永不过期。 */
    public record CreateApiKeyRequest(String tenant, String user,
                                      List<String> agentGrants, List<String> allowedModels,
                                      String expiresAt) {}

    /** 过期时间解析：兼容 ISO-8601 与 YYYY-MM-DD（取当日 UTC 23:59:59）。非法值抛 400。 */
    private static java.time.Instant parseExpiresAt(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        try {
            if (s.length() == 10) {
                return java.time.LocalDate.parse(s)
                        .atTime(java.time.LocalTime.MAX)
                        .toInstant(java.time.ZoneOffset.UTC);
            }
            return java.time.Instant.parse(s);
        } catch (Exception e) {
            // 语义为 400（参数错误），ResponseStatusException 保证不被映射成 500
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "非法 expiresAt: " + s + "（需 ISO-8601 或 YYYY-MM-DD）");
        }
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}
