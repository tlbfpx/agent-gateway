package com.company.agentgateway.infra.persistence.mcp;

import com.company.agentgateway.domain.mcp.McpPort;
import com.company.agentgateway.domain.mcp.McpServer;
import com.company.agentgateway.domain.mcp.McpTool;
import com.company.agentgateway.domain.mcp.McpToolCall;
import com.company.agentgateway.domain.mcp.McpToolResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MCP Server 内存实现（spec 2026-09-02 §mcp §4 P0）。
 *
 * <p>{@code CopyOnWriteArrayList} 存 server 注册表;{@code Map<serverId, List<McpTool>>}
 * 存每个 server 的 tool 列表(P0 静态;R15 转发到远端 server)。
 *
 * <p>{@link #callTool(McpToolCall)} 在 P0 用本地 {@code toolCallHandlers} map 做 stub:
 * 默认返回 expected-shape text block;R15 接 ChatOrchestrator 工具端口。
 */
public class InMemoryMcpServerRepository implements McpPort {

    private static final Logger log = LoggerFactory.getLogger(InMemoryMcpServerRepository.class);

    private final CopyOnWriteArrayList<McpServer> servers = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<McpTool>> toolsByServer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.util.function.Function<McpToolCall, McpToolResult>> handlers =
            new ConcurrentHashMap<>();

    public InMemoryMcpServerRepository() {
        registerDefaults();
    }

    /** P0 内置 2 个示例 server + 4 个工具,演示 MCP 端点能力 */
    private void registerDefaults() {
        registerServer(new McpServer(
                "builtin-time", "Time Utils", "时间格式化与时区工具",
                "internal://builtin/time", null, null));
        registerServer(new McpServer(
                "builtin-echo", "Echo Tools", "echo / reverse / upper 工具集",
                "internal://builtin/echo", null, null));

        registerTool("builtin-time", new McpTool(
                "current_time", "返回当前时间 ISO 8601",
                Map.of("type", "object", "properties", Map.of("tz", Map.of("type", "string")))));
        registerTool("builtin-time", new McpTool(
                "format", "按 pattern 格式化时间(简版)",
                Map.of("type", "object", "properties",
                        Map.of("iso", Map.of("type", "string"),
                                "pattern", Map.of("type", "string")))));

        registerTool("builtin-echo", new McpTool(
                "echo", "原样返回 arguments",
                Map.of("type", "object")));
        registerTool("builtin-echo", new McpTool(
                "upper", "转大写",
                Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")))));

        // 默认 handler:返回 arguments JSON 字符串
        registerHandler("builtin-time:current_time", call ->
                McpToolResult.text(java.time.Instant.now().toString()));
        registerHandler("builtin-time:format", call -> {
            String iso = String.valueOf(call.arguments().getOrDefault("iso", ""));
            try {
                return McpToolResult.text(java.time.Instant.parse(iso).toString());
            } catch (Exception ex) {
                return McpToolResult.error("invalid iso: " + iso);
            }
        });
        registerHandler("builtin-echo:echo", call ->
                McpToolResult.text(call.arguments().toString()));
        registerHandler("builtin-echo:upper", call -> {
            String text = String.valueOf(call.arguments().getOrDefault("text", ""));
            return McpToolResult.text(text.toUpperCase());
        });
    }

    public void registerServer(McpServer server) {
        servers.add(server);
        toolsByServer.putIfAbsent(server.id(), new CopyOnWriteArrayList<>());
        log.info("mcp.server.registered id={} name={}", server.id(), server.name());
    }

    public void registerTool(String serverId, McpTool tool) {
        toolsByServer.computeIfAbsent(serverId, k -> new CopyOnWriteArrayList<>()).add(tool);
    }

    public void registerHandler(String serverToolKey,
                                java.util.function.Function<McpToolCall, McpToolResult> handler) {
        handlers.put(serverToolKey, handler);
    }

    @Override
    public McpServer register(McpServer server) {
        registerServer(server);
        return server;
    }

    @Override
    public Optional<McpServer> findById(String id) {
        return servers.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    @Override
    public List<McpServer> listAll() {
        return List.copyOf(servers);
    }

    @Override
    public List<McpTool> listTools(String serverId) {
        return List.copyOf(toolsByServer.getOrDefault(serverId, new CopyOnWriteArrayList<>()));
    }

    @Override
    public McpToolResult callTool(McpToolCall call) {
        String key = call.serverId() + ":" + call.toolName();
        var handler = handlers.get(key);
        if (handler == null) {
            return McpToolResult.error("no handler for " + key);
        }
        try {
            return handler.apply(call);
        } catch (Exception ex) {
            log.warn("mcp.tool.call.error key={}: {}", key, ex.getMessage());
            return McpToolResult.error(ex.getMessage());
        }
    }
}
