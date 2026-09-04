package com.company.agentgateway.domain.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具调用结果（spec 2026-09-02 §mcp §3.5）。
 *
 * <p>{@code content} 是 content block 列表(MCP 允许 text/image/resource 三类);
 * P0 仅 text 类型。
 */
public record McpToolResult(
        boolean isError,
        List<ContentBlock> content,
        Map<String, Object> metadata) {

    public record ContentBlock(String type, String text, String mimeType) {
        public ContentBlock {
            if (type == null) type = "text";
            if (text == null) text = "";
        }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("text", text);
            if (mimeType != null) m.put("mimeType", mimeType);
            return m;
        }
    }

    public McpToolResult {
        content = content == null ? List.of() : List.copyOf(content);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static McpToolResult text(String text) {
        return new McpToolResult(false, List.of(new ContentBlock("text", text, null)), Map.of());
    }

    public static McpToolResult error(String message) {
        return new McpToolResult(true, List.of(new ContentBlock("text", message, null)), Map.of());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("isError", isError);
        m.put("content", content.stream().map(ContentBlock::toMap).toList());
        if (!metadata.isEmpty()) m.put("metadata", metadata);
        return m;
    }
}
