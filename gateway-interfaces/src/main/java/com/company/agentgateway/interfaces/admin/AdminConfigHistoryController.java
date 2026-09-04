package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.shared.TenantId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import com.company.agentgateway.application.config.ConfigHistory;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 配置版本历史与回滚端点（spec §20）。
 *
 * <ul>
 *   <li>GET  /v1/admin/config/{name}/versions          — 历史列表</li>
 *   <li>POST /v1/admin/config/{name}/rollback?version=  — 回滚</li>
 * </ul>
 *
 * <p>name ∈ models | api-keys。回滚 models 后需重启生效（FileModelRegistry 启动加载）；
 * api-keys 同理——热重载二期（与 Nacos 配置中心路径合并）。
 * 历史在每次写盘后自动快照（JsonFile*Store 调 ConfigHistory.snapshot）。
 */
@RestController
@RequestMapping("/v1/admin/config")
class AdminConfigHistoryController {

    private final ConfigHistory models;
    private final ConfigHistory apiKeys;
    private final AuditRepository auditRepository;

    AdminConfigHistoryController(
            @org.springframework.beans.factory.annotation.Qualifier("modelConfigHistory") ConfigHistory models,
            @org.springframework.beans.factory.annotation.Qualifier("apiKeyConfigHistory") ConfigHistory apiKeys,
            AuditRepository auditRepository) {
        this.models = models;
        this.apiKeys = apiKeys;
        this.auditRepository = auditRepository;
    }

    private ConfigHistory of(String name) {
        return switch (name) {
            case "models" -> models;
            case "api-keys" -> apiKeys;
            default -> throw new IllegalArgumentException("unknown config: " + name);
        };
    }

    @GetMapping("/{name}/versions")
    public List<Map<String, Object>> versions(@PathVariable String name) {
        return of(name).list().stream()
                .map(v -> Map.<String, Object>of(
                        "version", v.file(),
                        "at", v.at().toString(),
                        "size", v.size()))
                .toList();
    }

    @GetMapping("/{name}/diff")
    public java.util.Map<String, Object> diff(@PathVariable String name,
                                             @RequestParam String from,
                                             @RequestParam String to) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var a = flatten(mapper.readTree(of(name).readVersion(from)));
        var b = flatten(mapper.readTree(of(name).readVersion(to)));
        java.util.Map<String, java.util.List<String>> out = new java.util.LinkedHashMap<>();
        java.util.TreeSet<String> keys = new java.util.TreeSet<>();
        keys.addAll(a.keySet()); keys.addAll(b.keySet());
        for (String k : keys) {
            String va = a.containsKey(k) ? String.valueOf(a.get(k)) : null;
            String vb = b.containsKey(k) ? String.valueOf(b.get(k)) : null;
            if (!va.equals(vb)) out.put(k, java.util.Arrays.asList(va, vb));
        }
        return java.util.Map.of("fields", out, "changed", out.size());
    }

    private static java.util.Map<String, Object> flatten(com.fasterxml.jackson.databind.JsonNode n) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        flattenInto(n, "", out);
        return out;
    }

    private static void flattenInto(com.fasterxml.jackson.databind.JsonNode n, String prefix, java.util.Map<String, Object> out) {
        if (n.isObject()) n.fields().forEachRemaining(e -> flattenInto(e.getValue(), prefix + "." + e.getKey(), out));
        else if (n.isArray()) for (int i = 0; i < n.size(); i++) flattenInto(n.get(i), prefix + "[" + i + "]", out);
        else if (n.isNull()) out.put(prefix.substring(1), null);
        else out.put(prefix.substring(1), n.asText());
    }

    @PostMapping("/{name}/rollback")
    public Map<String, Object> rollback(@PathVariable String name,
                                        @RequestParam String version) {
        boolean ok = of(name).rollback(version);
        if (ok) {
            auditRepository.append(new AuditRepository.AuditLog(
                    UUID.randomUUID().toString(),
                    new TenantId("default"),
                    "admin",
                    AuditRepository.AuditLog.ActorType.HUMAN,
                    AuditRepository.AuditEventType.MODEL_CONFIG_UPDATE,
                    Instant.now(),
                    "config",
                    name + "@" + version,
                    "ROLLBACK",
                    AuditRepository.AuditLog.Result.SUCCESS,
                    null));
        }
        return ok
                ? Map.of("rolledBack", name, "version", version,
                        "note", "需重启后端生效（File*Store 启动加载；热重载二期）")
                : Map.of("error", "version not found: " + version);
    }
}
