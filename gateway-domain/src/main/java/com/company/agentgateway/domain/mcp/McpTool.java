package com.company.agentgateway.domain.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP Tool 描述（spec 2026-09-02 §mcp §3.3）。
 *
 * <p>对应 MCP protocol 的 {@code tools/list} 返回。
 * {@code inputSchema} 是 JSON Schema(Map 形式,实际使用 Object/Array/类型树)。
 */
public record McpTool(
        String name,
        String description,
        Map<String, Object> inputSchema) {

    public McpTool {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description == null ? "" : description);
        m.put("inputSchema", inputSchema);
        return m;
    }
}
