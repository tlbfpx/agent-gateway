package com.company.agentgateway.interfaces.plugin;

import com.company.agentgateway.application.plugin.PluginManager;
import com.company.agentgateway.application.plugin.PluginSandbox;
import com.company.agentgateway.domain.plugin.Plugin;
import com.company.agentgateway.domain.plugin.PluginRegistry;
import com.company.agentgateway.domain.plugin.PluginRequest;
import com.company.agentgateway.domain.plugin.PluginResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件管理 + 执行端点（spec 2026-09-02 §wasm-plugins §8）。
 *
 * <ul>
 *   <li>{@code GET /v1/admin/plugins} —— 列出所有插件</li>
 *   <li>{@code GET /v1/admin/plugins/{id}} —— 查单个</li>
 *   <li>{@code POST /v1/admin/plugins/test} —— 跑沙箱(请求 → 响应)</li>
 *   <li>{@code POST /v1/admin/plugins/{id}/disable} —— 注销(R15+2:热重载)</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin/plugins")
public class PluginController {

    private final PluginRegistry registry;
    private final PluginSandbox sandbox;
    private final PluginManager manager;

    public PluginController(PluginRegistry registry, PluginSandbox sandbox, PluginManager manager) {
        this.registry = registry;
        this.sandbox = sandbox;
        this.manager = manager;
    }

    @GetMapping
    public List<Map<String, Object>> listPlugins() {
        return registry.listAll().stream()
                .map(p -> p.descriptor().toMap())
                .toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> getPlugin(@PathVariable String id) {
        return registry.findById(id)
                .map(p -> p.descriptor().toMap())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "plugin not found: " + id));
    }

    @PostMapping("/test")
    public Map<String, Object> testSandbox(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestBody Map<String, Object> body) {
        String path = stringOrNull(body, "path");
        String method = stringOrNull(body, "method");
        String reqBody = stringOrNull(body, "body");
        String tenant = stringOrNull(body, "tenant");
        @SuppressWarnings("unchecked")
        Map<String, String> headers = body.get("headers") instanceof Map
                ? (Map<String, String>) body.get("headers") : Map.of();
        PluginRequest req = new PluginRequest(path, method, headers, reqBody, tenant, apiKey);
        PluginResponse resp = sandbox.execute(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", resp.status());
        out.put("headers", resp.headers());
        out.put("body", resp.body());
        out.put("blocked", resp.blocked());
        if (resp.blockReason() != null) out.put("blockReason", resp.blockReason());
        return out;
    }

    @PostMapping("/{id}/disable")
    public Map<String, Object> disablePlugin(@PathVariable String id) {
        boolean ok = registry.unregister(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("disabled", ok);
        out.put("id", id);
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "plugin not found: " + id);
        }
        return out;
    }

    @PostMapping("/reload")
    public Map<String, Object> reload() {
        manager.bootstrap();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reloaded", true);
        out.put("total", registry.listAll().size());
        return out;
    }

    private static String stringOrNull(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : v.toString();
    }
}