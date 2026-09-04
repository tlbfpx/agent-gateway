package com.company.agentgateway.interfaces.mcp;

import com.company.agentgateway.application.mcp.McpJsonRpcDispatcher;
import com.company.agentgateway.domain.mcp.McpJsonRpc;
import com.company.agentgateway.domain.mcp.McpServer;
import com.company.agentgateway.domain.mcp.McpTool;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 协议端点（spec 2026-09-02 §mcp §6）。
 *
 * <p>两类端点：
 * <ul>
 *   <li>{@code POST /v1/mcp} —— JSON-RPC 2.0 单次请求-响应(spec 主流)</li>
 *   <li>{@code GET /v1/mcp/servers} —— 列出已注册 server(管理端)</li>
 * </ul>
 *
 * <p>鉴权：X-API-Key（与 chat 链路一致）；R15 加 OAuth 2.1。
 */
@RestController
@RequestMapping("/v1/mcp")
public class McpController {

    private final McpJsonRpcDispatcher dispatcher;
    private final com.company.agentgateway.domain.mcp.McpPort port;

    public McpController(McpJsonRpcDispatcher dispatcher, com.company.agentgateway.domain.mcp.McpPort port) {
        this.dispatcher = dispatcher;
        this.port = port;
    }

    private void requireApiKey(String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-API-Key required");
        }
    }

    /** JSON-RPC 2.0 single endpoint。 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> jsonRpc(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestBody Map<String, Object> body) {
        requireApiKey(apiKey);
        // 验证 JSON-RPC 2.0 envelope
        if (!"2.0".equals(body.get("jsonrpc"))) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(McpJsonRpc.error(body.get("id"), McpJsonRpc.INVALID_REQUEST,
                            "jsonrpc field must be \"2.0\""));
        }
        Object id = body.get("id");
        Object methodRaw = body.get("method");
        if (!(methodRaw instanceof String)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(McpJsonRpc.error(id, McpJsonRpc.INVALID_REQUEST, "method must be string"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> params = body.get("params") instanceof Map
                ? (Map<String, Object>) body.get("params")
                : Map.of();
        Map<String, Object> result = dispatcher.dispatch(id, (String) methodRaw, params);
        // JSON-RPC 通知(notification,id 缺失)返回 204 No Content
        if (!body.containsKey("id")) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(result);
    }

    /** 列出已注册 server (管理端) */
    @GetMapping("/servers")
    public List<Map<String, Object>> listServers(
            @RequestHeader("X-API-Key") String apiKey) {
        requireApiKey(apiKey);
        return port.listAll().stream().map(McpServer::toMap).toList();
    }

    /** 列出某 server 的工具 */
    @GetMapping("/servers/{serverId}/tools")
    public Map<String, Object> listTools(
            @RequestHeader("X-API-Key") String apiKey,
            @org.springframework.web.bind.annotation.PathVariable String serverId) {
        requireApiKey(apiKey);
        McpServer s = port.findById(serverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "server not found"));
        List<McpTool> tools = port.listTools(serverId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("server", s.toMap());
        out.put("tools", tools.stream().map(McpTool::toMap).toList());
        return out;
    }
}
