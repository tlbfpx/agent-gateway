package com.company.agentgateway.domain.plugin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 插件请求上下文（spec 2026-09-02 §wasm-plugins §3.3）。
 */
public record PluginRequest(
        String path,
        String method,
        Map<String, String> headers,
        String body,
        String tenant,
        String apiKey) {

    public PluginRequest {
        if (path == null) path = "";
        if (method == null) method = "GET";
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        if (body == null) body = "";
        if (tenant == null) tenant = "default";
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("method", method);
        m.put("headers", headers);
        m.put("body", body);
        m.put("tenant", tenant);
        m.put("apiKey", apiKey == null ? "" : apiKey);
        return m;
    }
}