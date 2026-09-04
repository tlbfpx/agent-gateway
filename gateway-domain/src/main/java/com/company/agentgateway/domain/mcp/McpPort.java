package com.company.agentgateway.domain.mcp;

import java.util.List;
import java.util.Optional;

/**
 * MCP Server 持久化端口（spec 2026-09-02 §mcp §4）。
 *
 * <p>实现：
 * <ul>
 *   <li>P0：{@code InMemoryMcpServerRepository}</li>
 *   <li>P1：{@code PgMcpServerRepository}</li>
 * </ul>
 */
public interface McpPort {

    /** 注册 MCP server */
    McpServer register(McpServer server);

    /** 按 id 查 */
    Optional<McpServer> findById(String id);

    /** 列所有 server */
    List<McpServer> listAll();

    /** 列某个 server 的工具列表(P0 直接由 in-memory 提供;R15 可转发到远端 server) */
    List<McpTool> listTools(String serverId);

    /** 调用工具 */
    McpToolResult callTool(McpToolCall call);
}
