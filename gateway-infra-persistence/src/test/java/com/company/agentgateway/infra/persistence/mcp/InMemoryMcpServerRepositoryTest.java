package com.company.agentgateway.infra.persistence.mcp;

import com.company.agentgateway.domain.mcp.McpServer;
import com.company.agentgateway.domain.mcp.McpTool;
import com.company.agentgateway.domain.mcp.McpToolCall;
import com.company.agentgateway.domain.mcp.McpToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryMcpServerRepositoryTest {

    private InMemoryMcpServerRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryMcpServerRepository();
    }

    @Test
    void defaultServersPresent() {
        List<McpServer> all = repo.listAll();
        assertTrue(all.stream().anyMatch(s -> s.id().equals("builtin-time")));
        assertTrue(all.stream().anyMatch(s -> s.id().equals("builtin-echo")));
    }

    @Test
    void listTools_returnsExpected() {
        List<McpTool> timeTools = repo.listTools("builtin-time");
        assertEquals(2, timeTools.size());
        assertTrue(timeTools.stream().anyMatch(t -> t.name().equals("current_time")));
    }

    @Test
    void callTool_echoWorks() {
        McpToolCall c = new McpToolCall("builtin-echo", "echo", Map.of("hi", "there"), null, null);
        McpToolResult r = repo.callTool(c);
        assertFalse(r.isError());
        assertEquals(1, r.content().size());
        assertTrue(r.content().get(0).text().contains("hi"));
    }

    @Test
    void callTool_upperCase() {
        McpToolCall c = new McpToolCall("builtin-echo", "upper", Map.of("text", "hello"), null, null);
        McpToolResult r = repo.callTool(c);
        assertFalse(r.isError());
        assertEquals("HELLO", r.content().get(0).text());
    }

    @Test
    void callTool_unknown_returnsError() {
        McpToolCall c = new McpToolCall("builtin-echo", "no-such-tool", Map.of(), null, null);
        McpToolResult r = repo.callTool(c);
        assertTrue(r.isError());
    }

    @Test
    void registerCustomServer() {
        McpServer s = new McpServer("custom", "Custom", "", "internal://custom", null, null);
        repo.register(s);
        assertNotNull(repo.findById("custom").orElse(null));
    }
}
