package com.company.agentgateway.domain.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 工具调用请求（spec 2026-09-02 §mcp §3.4）。
 *
 * <p>{@code arguments} 是 JSON object(扁平 Map);size 限制由 controller 层校验。
 */
public record McpToolCall(
        String serverId,
        String toolName,
        Map<String, Object> arguments,
        String requestId,
        String sessionId) {

    public McpToolCall {
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException("serverId must not be blank");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("serverId", serverId);
        m.put("toolName", toolName);
        m.put("arguments", arguments);
        m.put("requestId", requestId == null ? "" : requestId);
        m.put("sessionId", sessionId == null ? "" : sessionId);
        return m;
    }
}
