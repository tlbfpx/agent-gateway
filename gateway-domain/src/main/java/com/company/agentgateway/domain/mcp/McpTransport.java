package com.company.agentgateway.domain.mcp;

/**
 * MCP 传输层枚举（spec 2026-09-02 §mcp §3.1）。
 *
 * <p>P0 仅支持 HTTP+SSE;stdio 留 R15。
 * Streamable HTTP 是 2025-06 spec 主流。
 */
public enum McpTransport {
    HTTP_SSE,
    HTTP_STREAMABLE,
    STDIO;

    public static McpTransport parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("transport must not be blank");
        }
        try {
            return McpTransport.valueOf(raw.trim().toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown transport: " + raw
                    + " (use HTTP_SSE|HTTP_STREAMABLE|STDIO)");
        }
    }
}
