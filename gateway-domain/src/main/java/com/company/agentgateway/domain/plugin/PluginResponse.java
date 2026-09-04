package com.company.agentgateway.domain.plugin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 插件响应（spec 2026-09-02 §wasm-plugins §3.4）。
 *
 * <p>插件可修改 headers / body / status;PluginSandbox 收集最终响应。
 */
public record PluginResponse(
        int status,
        Map<String, String> headers,
        String body,
        boolean blocked,
        String blockReason) {

    public PluginResponse {
        status = status <= 0 ? 200 : status;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        if (body == null) body = "";
    }

    public static PluginResponse passthrough() {
        return new PluginResponse(200, Map.of(), "", false, null);
    }

    public static PluginResponse blocked(String reason) {
        return new PluginResponse(429, Map.of(), "{\"error\":\"rate_limited\"}", true, reason);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("headers", headers);
        m.put("body", body);
        m.put("blocked", blocked);
        if (blockReason != null) m.put("blockReason", blockReason);
        return m;
    }
}