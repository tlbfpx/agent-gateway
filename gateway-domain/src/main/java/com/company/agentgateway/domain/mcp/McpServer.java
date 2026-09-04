package com.company.agentgateway.domain.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP Server 注册项（spec 2026-09-02 §mcp §3.2）。
 *
 * <p>远端 MCP server;agent-gateway 作为 MCP client 代理调用。
 * 当前 P0 用于把多个 MCP server 统一注册到 gateway,客户端只需连 gateway 一个端口。
 */
public record McpServer(
        String id,
        String name,
        String description,
        String endpoint,
        McpTransport transport,
        String protocolVersion) {

    public McpServer {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint must not be blank");
        }
        if (transport == null) transport = McpTransport.HTTP_STREAMABLE;
        if (protocolVersion == null) protocolVersion = "2025-06-18";
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("description", description == null ? "" : description);
        m.put("endpoint", endpoint);
        m.put("transport", transport.name());
        m.put("protocolVersion", protocolVersion);
        return m;
    }
}
