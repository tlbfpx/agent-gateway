package com.company.agentgateway.domain.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON-RPC 2.0 工具类（spec 2026-09-02 §mcp §5）。
 *
 * <p>标准错误码（JSON-RPC 2.0 spec）：
 * <ul>
 *   <li>-32700 — Parse error</li>
 *   <li>-32600 — Invalid Request</li>
 *   <li>-32601 — Method not found</li>
 *   <li>-32602 — Invalid params</li>
 *   <li>-32603 — Internal error</li>
 *   <li>-32000 ~ -32099 — Server error（reserved）</li>
 * </ul>
 */
public final class McpJsonRpc {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    private McpJsonRpc() {}

    /** 成功响应 */
    public static Map<String, Object> success(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        if (id != null) m.put("id", id);
        m.put("result", result);
        return m;
    }

    /** 错误响应 */
    public static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        if (id != null) m.put("id", id);
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message == null ? "" : message);
        m.put("error", err);
        return m;
    }
}
