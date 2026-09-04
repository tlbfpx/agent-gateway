package com.company.agentgateway.application.plugin.builtin;

import com.company.agentgateway.domain.plugin.Plugin;
import com.company.agentgateway.domain.plugin.PluginCapability;
import com.company.agentgateway.domain.plugin.PluginDescriptor;
import com.company.agentgateway.domain.plugin.PluginRequest;
import com.company.agentgateway.domain.plugin.PluginResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AuditPlugin —— 每次请求记一条结构化审计日志 (Round 15 §wasm-plugins)。
 *
 * <p>官方样本插件 #3,演示 AUDIT + LOG 能力。
 * P0 写 SLF4J;R15+2 接 audit 模块 append 到 AuditRepository。
 */
public class AuditPlugin implements Plugin {

    private static final Logger log = LoggerFactory.getLogger(AuditPlugin.class);

    public static final String ID = "builtin-audit";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(
                ID, "Audit Logger", "1.0.0",
                "每次请求记录结构化审计日志(method/path/tenant)",
                PluginDescriptor.PluginFormat.JAVA,
                Set.of(PluginCapability.AUDIT, PluginCapability.LOG),
                List.of("audit", "builtin"),
                true);
    }

    @Override
    public PluginResponse handle(PluginRequest request) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "plugin_audit");
        entry.put("method", request.method());
        entry.put("path", request.path());
        entry.put("tenant", request.tenant());
        entry.put("apiKey", request.apiKey() == null ? "" : maskApiKey(request.apiKey()));
        entry.put("headers", request.headers().keySet());
        log.info("plugin.audit {}", entry);
        return new PluginResponse(200, request.headers(), request.body(), false, null);
    }

    private static String maskApiKey(String k) {
        if (k == null || k.length() < 8) return "***";
        return k.substring(0, 4) + "***" + k.substring(k.length() - 4);
    }
}