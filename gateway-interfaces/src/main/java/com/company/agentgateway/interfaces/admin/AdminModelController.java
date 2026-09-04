package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.model.Capability;
import com.company.agentgateway.domain.model.ModelDef;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.infra.llm.model.JsonFileModelRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 模型管理端点（spec §17，管理员菜单）。
 *
 * <ul>
 *   <li>GET    /v1/admin/models：模型列表（含 provider/modelName，apiKey 脱敏）</li>
 *   <li>POST   /v1/admin/models：新增模型（provider/key/modelName 等），即时生效</li>
 *   <li>PUT    /v1/admin/models/{id}：更新</li>
 *   <li>DELETE /v1/admin/models/{id}：删除</li>
 * </ul>
 *
 * <p>管理员配置好后，用户在对话框模型下拉直接选用（/v1/models 返回全部启用模型）。
 */
@RestController
@RequestMapping("/v1/admin/models")
public class AdminModelController {

    private final JsonFileModelRegistry registry;
    private final AuditRepository auditRepository;

    public AdminModelController(JsonFileModelRegistry registry, AuditRepository auditRepository) {
        this.registry = registry;
        this.auditRepository = auditRepository;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return registry.listModels().stream().map(AdminModelController::toView).toList();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody ModelDto dto) {
        require(dto.id(), "id");
        require(dto.provider(), "provider");
        if (registry.getModel(new ModelId(dto.id())).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Model already exists: " + dto.id());
        }
        ModelDef def = registry.upsert(toDef(dto));
        auditRepository.append(new AuditRepository.AuditLog(
                UUID.randomUUID().toString(),
                new TenantId("default"),
                "admin",
                AuditRepository.AuditLog.ActorType.HUMAN,
                AuditRepository.AuditEventType.MODEL_CONFIG_UPDATE,
                Instant.now(),
                "model",
                def.id().value(),
                "CREATE",
                AuditRepository.AuditLog.Result.SUCCESS,
                null));
        return toView(def);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody ModelDto dto) {
        ModelDef existing = registry.getModel(new ModelId(id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Model not found: " + id));
        // 未传 apiKey 则保留原值（更新时不必重填 key）
        String apiKey = (dto.apiKey() == null || dto.apiKey().isBlank())
                ? existing.apiKeyRef() : dto.apiKey();
        ModelDef def = new ModelDef(
                new ModelId(id),
                or(dto.provider(), existing.provider()),
                or(dto.displayName(), existing.displayName()),
                or(dto.endpoint(), existing.endpoint()),
                apiKey,
                dto.capabilities() == null ? existing.capabilities() : caps(dto.capabilities()),
                dto.contextWindow() != null && dto.contextWindow() > 0 ? dto.contextWindow() : existing.contextWindow(),
                dto.costPer1kIn() != null ? dto.costPer1kIn() : existing.costPer1kIn(),
                dto.costPer1kOut() != null ? dto.costPer1kOut() : existing.costPer1kOut(),
                dto.enabled() == null ? existing.enabled() : dto.enabled(),
                existing.tenantScope(),
                or(dto.modelName(), existing.modelName()),
                dto.weight != null ? dto.weight : existing.normalizedWeight());
        registry.upsert(def);
        auditRepository.append(new AuditRepository.AuditLog(
                UUID.randomUUID().toString(),
                new TenantId("default"),
                "admin",
                AuditRepository.AuditLog.ActorType.HUMAN,
                AuditRepository.AuditEventType.MODEL_CONFIG_UPDATE,
                Instant.now(),
                "model",
                id,
                "UPDATE",
                AuditRepository.AuditLog.Result.SUCCESS,
                null));
        return toView(def);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        boolean removed = registry.delete(new ModelId(id));
        if (!removed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Model not found: " + id);
        }
        auditRepository.append(new AuditRepository.AuditLog(
                UUID.randomUUID().toString(),
                new TenantId("default"),
                "admin",
                AuditRepository.AuditLog.ActorType.HUMAN,
                AuditRepository.AuditEventType.MODEL_CONFIG_UPDATE,
                Instant.now(),
                "model",
                id,
                "DELETE",
                AuditRepository.AuditLog.Result.SUCCESS,
                null));
        return Map.of("deleted", id);
    }

    // ─── helpers ───

    private static ModelDef toDef(ModelDto dto) {
        return new ModelDef(
                new ModelId(dto.id()),
                dto.provider(),
                or(dto.displayName(), dto.id()),
                or(dto.endpoint(), ""),
                or(dto.apiKey(), ""),
                caps(dto.capabilities() == null ? List.of() : dto.capabilities()),
                dto.contextWindow() == null ? 8192 : dto.contextWindow(),
                dto.costPer1kIn() == null ? BigDecimal.ZERO : dto.costPer1kIn(),
                dto.costPer1kOut() == null ? BigDecimal.ZERO : dto.costPer1kOut(),
                dto.enabled() == null || dto.enabled(),
                List.of("all"),
                dto.modelName(),
                dto.weight == null ? 100 : dto.weight());
    }

    private static Map<String, Object> toView(ModelDef d) {
        return Map.of(
                "id", d.id().value(),
                "provider", d.provider(),
                "displayName", d.displayName(),
                "endpoint", d.endpoint(),
                "apiKeyMasked", mask(d.apiKeyRef()),
                "capabilities", d.capabilities().stream().map(Enum::name).toList(),
                "contextWindow", d.contextWindow(),
                "enabled", d.enabled(),
                "modelName", d.modelNameOrId(),
                "weight", d.normalizedWeight());
    }

    /** apiKey 脱敏：只露前 6 后 4。 */
    private static String mask(String key) {
        if (key == null || key.length() < 12) {
            return "****";
        }
        return key.substring(0, 6) + "****" + key.substring(key.length() - 4);
    }

    private static Set<Capability> caps(List<String> raw) {
        return raw.stream()
                .map(s -> {
                    try {
                        return Capability.valueOf(s.trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String or(String v, String dft) {
        return (v == null || v.isBlank()) ? dft : v;
    }

    private static void require(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: " + field);
        }
    }

    /** 模型配置 DTO。 */
    public record ModelDto(
            String id,
            String provider,
            String displayName,
            String endpoint,
            String apiKey,
            List<String> capabilities,
            Integer contextWindow,
            BigDecimal costPer1kIn,
            BigDecimal costPer1kOut,
            Boolean enabled,
            String modelName,
            Integer weight) {}
}
