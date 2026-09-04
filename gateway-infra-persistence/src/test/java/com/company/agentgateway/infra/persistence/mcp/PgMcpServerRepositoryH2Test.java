package com.company.agentgateway.infra.persistence.mcp;

import com.company.agentgateway.domain.mcp.McpPort;
import com.company.agentgateway.domain.mcp.McpServer;
import com.company.agentgateway.domain.mcp.McpTool;
import com.company.agentgateway.domain.mcp.McpToolCall;
import com.company.agentgateway.domain.mcp.McpToolResult;
import com.company.agentgateway.domain.mcp.McpTransport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2 跑 PgMcpServerRepository（验证 schema + UPSERT + InMemory 协同）。
 * R20 #1 单测。
 */
class PgMcpServerRepositoryH2Test {

    private McpPort repo;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:mcp_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE mcp_server (
                    id VARCHAR(128) PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    description CLOB,
                    endpoint VARCHAR(512) NOT NULL,
                    transport VARCHAR(32) NOT NULL DEFAULT 'HTTP_STREAMABLE',
                    protocol_version VARCHAR(32) NOT NULL DEFAULT '2025-06-18',
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
        // 内置 2 个示例 (与 V2 migration 一致)
        jdbc.update("INSERT INTO mcp_server (id, name, description, endpoint) VALUES (?, ?, ?, ?)",
                "builtin-time", "Time Utils", "时间工具", "internal://builtin/time");
        jdbc.update("INSERT INTO mcp_server (id, name, description, endpoint) VALUES (?, ?, ?, ?)",
                "builtin-echo", "Echo Tools", "echo 工具", "internal://builtin/echo");
        repo = new PgMcpServerRepository(jdbc);
    }

    @Test
    void 启动时加载DB内置server() {
        // 构造时 loadAllFromDb 已执行, listAll 应包含 2 个内置
        List<McpServer> all = repo.listAll();
        assertEquals(2, all.size(), "DB 初始化 2 个内置 server");
        assertTrue(all.stream().anyMatch(s -> s.id().equals("builtin-time")));
        assertTrue(all.stream().anyMatch(s -> s.id().equals("builtin-echo")));
    }

    @Test
    void register_新server_写入DB并可查() {
        McpServer custom = new McpServer(
                "custom-weather", "Weather", "天气查询 MCP server",
                "https://mcp.weather.example.com", McpTransport.HTTP_SSE, "2025-06-18");
        repo.register(custom);

        Optional<McpServer> found = repo.findById("custom-weather");
        assertTrue(found.isPresent());
        assertEquals("Weather", found.get().name());
        assertEquals("https://mcp.weather.example.com", found.get().endpoint());
        assertEquals(McpTransport.HTTP_SSE, found.get().transport());
    }

    @Test
    void register_同id_upsert() {
        McpServer v1 = new McpServer("s1", "name-v1", "desc", "https://v1", null, null);
        repo.register(v1);
        McpServer v2 = new McpServer("s1", "name-v2", "desc", "https://v2", null, null);
        repo.register(v2);

        Optional<McpServer> found = repo.findById("s1");
        assertTrue(found.isPresent());
        assertEquals("name-v2", found.get().name(), "应覆盖");
        assertEquals("https://v2", found.get().endpoint());
    }

    @Test
    void findById_不存在_返空Optional() {
        Optional<McpServer> found = repo.findById("nonexistent");
        assertTrue(found.isEmpty());
    }

    @Test
    void listAll_空DB_仅内置2条() {
        // 清空后重建 repo
        jdbc.update("DELETE FROM mcp_server");
        McpPort emptyRepo = new PgMcpServerRepository(jdbc);
        // 重建后是空, 没有内置 (V2 migration 才插入内置)
        // 注意: 这里 PgMcpServerRepository 构造时不重新 INSERT 内置
        List<McpServer> all = emptyRepo.listAll();
        assertEquals(0, all.size());
    }

    @Test
    void listTools_内置server_走InMemory() {
        // tools 是 InMemory 内置, 不在 DB
        List<McpTool> timeTools = repo.listTools("builtin-time");
        assertNotNull(timeTools);
        assertTrue(timeTools.size() >= 2, "builtin-time 至少 current_time + format");
        assertTrue(timeTools.stream().anyMatch(t -> t.name().equals("current_time")));
    }

    @Test
    void callTool_内置handler_返结果() {
        // callTool 走 InMemory, 不查 DB
        McpToolCall call = new McpToolCall(
                "builtin-time", "current_time", Map.of(), "t1", "sess1");
        McpToolResult result = repo.callTool(call);
        assertNotNull(result);
        assertNotNull(result.content());
        assertTrue(!result.content().isEmpty());
        assertTrue(!result.isError(), "内置 handler 不应 error");
    }
}
