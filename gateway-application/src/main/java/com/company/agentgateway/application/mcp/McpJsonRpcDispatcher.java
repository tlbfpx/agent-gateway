package com.company.agentgateway.application.mcp;

import com.company.agentgateway.domain.mcp.McpJsonRpc;
import com.company.agentgateway.domain.mcp.McpPort;
import com.company.agentgateway.domain.mcp.McpServer;
import com.company.agentgateway.domain.mcp.McpTool;
import com.company.agentgateway.domain.mcp.McpToolCall;
import com.company.agentgateway.domain.mcp.McpToolResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP JSON-RPC 2.0 dispatcher（spec 2026-09-02 §mcp §5）。
 *
 * <p>三方法支持：
 * <ul>
 *   <li>{@code initialize} —— 返回 server capabilities</li>
 *   <li>{@code tools/list} —— 列出 server 的工具</li>
 *   <li>{@code tools/call} —— 调用工具</li>
 * </ul>
 *
 * <p>其余方法返回 {@link McpJsonRpc#METHOD_NOT_FOUND}。
 */
public class McpJsonRpcDispatcher {

    private static final Logger log = LoggerFactory.getLogger(McpJsonRpcDispatcher.class);

    public static final String METHOD_INITIALIZE = "initialize";
    public static final String METHOD_TOOLS_LIST = "tools/list";
    public static final String METHOD_TOOLS_CALL = "tools/call";

    public static final String PROTOCOL_VERSION = "2025-06-18";

    private final McpPort port;

    public McpJsonRpcDispatcher(McpPort port) {
        this.port = port;
    }

    /** 主入口:解析 JSON-RPC 请求,返回响应。 */
    public Map<String, Object> dispatch(Object id, String method, Map<String, Object> params) {
        if (method == null || method.isBlank()) {
            return McpJsonRpc.error(id, McpJsonRpc.INVALID_REQUEST, "method required");
        }
        try {
            return switch (method) {
                case METHOD_INITIALIZE -> handleInitialize(id, params);
                case METHOD_TOOLS_LIST -> handleToolsList(id, params);
                case METHOD_TOOLS_CALL -> handleToolsCall(id, params);
                default -> McpJsonRpc.error(id, McpJsonRpc.METHOD_NOT_FOUND, "unknown method: " + method);
            };
        } catch (IllegalArgumentException ex) {
            return McpJsonRpc.error(id, McpJsonRpc.INVALID_PARAMS, ex.getMessage());
        } catch (Exception ex) {
            log.error("mcp.dispatch.internal_error method={}: {}", method, ex.getMessage(), ex);
            return McpJsonRpc.error(id, McpJsonRpc.INTERNAL_ERROR, ex.getMessage());
        }
    }

    private Map<String, Object> handleInitialize(Object id, Map<String, Object> params) {
        // params 含 clientInfo/protocolVersion
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "agent-gateway");
        serverInfo.put("version", "1.0.0");
        result.put("serverInfo", serverInfo);
        Map<String, Object> capabilities = new LinkedHashMap<>();
        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("listChanged", false);
        capabilities.put("tools", tools);
        result.put("capabilities", capabilities);
        return McpJsonRpc.success(id, result);
    }

    private Map<String, Object> handleToolsList(Object id, Map<String, Object> params) {
        if (params == null) {
            return McpJsonRpc.error(id, McpJsonRpc.INVALID_PARAMS, "params required");
        }
        Object serverIdRaw = params.get("serverId");
        if (serverIdRaw == null) {
            return McpJsonRpc.error(id, McpJsonRpc.INVALID_PARAMS, "serverId required");
        }
        String serverId = serverIdRaw.toString();
        McpServer server = port.findById(serverId)
                .orElseThrow(() -> new IllegalArgumentException("server not found: " + serverId));
        List<McpTool> tools = port.listTools(server.id());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("server", server.toMap());
        result.put("tools", tools.stream().map(McpTool::toMap).toList());
        return McpJsonRpc.success(id, result);
    }

    private Map<String, Object> handleToolsCall(Object id, Map<String, Object> params) {
        if (params == null) {
            return McpJsonRpc.error(id, McpJsonRpc.INVALID_PARAMS, "params required");
        }
        Object serverIdRaw = params.get("serverId");
        Object toolNameRaw = params.get("name");
        Object argumentsRaw = params.get("arguments");
        if (serverIdRaw == null) {
            return McpJsonRpc.error(id, McpJsonRpc.INVALID_PARAMS, "serverId required");
        }
        if (toolNameRaw == null) {
            return McpJsonRpc.error(id, McpJsonRpc.INVALID_PARAMS, "name required");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = argumentsRaw instanceof Map ? (Map<String, Object>) argumentsRaw : Map.of();

        McpToolCall call = new McpToolCall(
                serverIdRaw.toString(), toolNameRaw.toString(), arguments, null, null);
        McpToolResult result = port.callTool(call);
        return McpJsonRpc.success(id, result.toMap());
    }
}
