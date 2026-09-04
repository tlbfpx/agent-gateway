package com.company.agentgateway.infra.persistence.mcp.remote;

import com.company.agentgateway.domain.mcp.McpJsonRpc;
import com.company.agentgateway.domain.mcp.McpToolCall;
import com.company.agentgateway.domain.mcp.McpToolResult;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 远端客户端（spec 2026-09-03 §mcp-forward §3）。
 *
 * <p>agent-gateway 作为 MCP client 调远端 MCP server。
 * 协议：JSON-RPC 2.0 over HTTP POST + SSE 响应(可选 streaming)。
 *
 * <p>P0 实现：JDK 11+ {@link HttpClient}(零依赖);同步请求;
 * R18+1 加 async streaming + 多 server 连接池。
 */
public class McpRemoteClient {

    private final HttpClient http;

    public McpRemoteClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    public McpRemoteClient(HttpClient http) {
        this.http = http;
    }

    /** 调 initialize;返回 serverInfo + capabilities */
    public Map<String, Object> initialize(String endpoint, Duration timeout) throws IOException, InterruptedException {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0", "id", "1", "method", "initialize",
                "params", Map.of("protocolVersion", "2025-06-18",
                        "clientInfo", Map.of("name", "agent-gateway", "version", "1.0.0"),
                        "capabilities", Map.of()));
        Map<String, Object> resp = post(endpoint, body, timeout);
        return (Map<String, Object>) resp.get("result");
    }

    /** 调 tools/list;返回 server 描述 + tool 列表 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> listTools(String endpoint, String serverId, Duration timeout)
            throws IOException, InterruptedException {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0", "id", "2", "method", "tools/list",
                "params", Map.of("serverId", serverId));
        Map<String, Object> resp = post(endpoint, body, timeout);
        return (Map<String, Object>) resp.get("result");
    }

    /** 调 tools/call;返回 McpToolResult */
    @SuppressWarnings("unchecked")
    public McpToolResult callTool(String endpoint, McpToolCall call, Duration timeout)
            throws IOException, InterruptedException {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0", "id", "3", "method", "tools/call",
                "params", Map.of(
                        "serverId", call.serverId(),
                        "name", call.toolName(),
                        "arguments", call.arguments()));
        Map<String, Object> resp = post(endpoint, body, timeout);
        if (resp.containsKey("error")) {
            Map<String, Object> err = (Map<String, Object>) resp.get("error");
            return McpToolResult.error("remote error: " + err.get("message"));
        }
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        Object isErrorRaw = result.getOrDefault("isError", Boolean.FALSE);
        boolean isError = isErrorRaw instanceof Boolean
                ? (Boolean) isErrorRaw
                : isErrorRaw instanceof Number && ((Number) isErrorRaw).intValue() != 0;
        @SuppressWarnings("unchecked")
        java.util.List<McpToolResult.ContentBlock> content =
                (java.util.List<McpToolResult.ContentBlock>) result.get("content");
        return new McpToolResult(isError, content, result);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String endpoint, Map<String, Object> body, Duration timeout)
            throws IOException, InterruptedException {
        String json = toJson(body);
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("MCP HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return fromJson(resp.body());
    }

    /** 极简 JSON encode(单层) */
    static String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(e.getKey())).append("\":");
            encodeValue(sb, e.getValue());
        }
        return sb.append("}").toString();
    }

    @SuppressWarnings("unchecked")
    static void encodeValue(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String s) { sb.append("\"").append(escape(s)).append("\""); return; }
        if (v instanceof Number || v instanceof Boolean) { sb.append(v); return; }
        if (v instanceof Map<?, ?> map) {
            sb.append("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escape(e.getKey().toString())).append("\":");
                encodeValue(sb, e.getValue());
            }
            sb.append("}");
            return;
        }
        if (v instanceof java.util.List<?> list) {
            sb.append("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(",");
                first = false;
                encodeValue(sb, item);
            }
            sb.append("]");
            return;
        }
        sb.append("\"").append(escape(v.toString())).append("\"");
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 极简 JSON decode(支持 string/number/boolean/null/object/array);
     * 足够 MCP 协议用(不处理转义序列深度;生产用 Jackson 替换)。
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> fromJson(String s) {
        return (Map<String, Object>) new JsonReader(s).parseValue();
    }

    static String stringOf(Object v) { return v == null ? "" : v.toString(); }
}
