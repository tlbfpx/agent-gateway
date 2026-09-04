package com.company.agentgateway.application.plugin.builtin;

import com.company.agentgateway.domain.plugin.Plugin;
import com.company.agentgateway.domain.plugin.PluginCapability;
import com.company.agentgateway.domain.plugin.PluginDescriptor;
import com.company.agentgateway.domain.plugin.PluginRequest;
import com.company.agentgateway.domain.plugin.PluginResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HeaderInjectPlugin —— 给响应注入 X-Gateway: agent-gateway (Round 15 §wasm-plugins)。
 *
 * <p>官方样本插件 #1,演示 HEADER_INJECT 能力。
 */
public class HeaderInjectPlugin implements Plugin {

    public static final String ID = "builtin-header-inject";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(
                ID, "Header Inject", "1.0.0",
                "在所有响应 headers 注入 X-Gateway: agent-gateway",
                PluginDescriptor.PluginFormat.JAVA,
                Set.of(PluginCapability.HEADER_INJECT),
                List.of("core", "builtin"),
                true);
    }

    @Override
    public PluginResponse handle(PluginRequest request) {
        Map<String, String> headers = new LinkedHashMap<>(request.headers());
        headers.put("X-Gateway", "agent-gateway");
        return new PluginResponse(200, headers, request.body(), false, null);
    }
}