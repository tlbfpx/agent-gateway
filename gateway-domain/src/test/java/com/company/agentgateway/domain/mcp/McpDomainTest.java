package com.company.agentgateway.domain.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpTransportTest {
    @Test
    void parse_knownNames() {
        assertEquals(McpTransport.HTTP_SSE, McpTransport.parse("http-sse"));
        assertEquals(McpTransport.HTTP_STREAMABLE, McpTransport.parse("HTTP_STREAMABLE"));
        assertEquals(McpTransport.STDIO, McpTransport.parse("stdio"));
    }
    @Test
    void parse_rejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> McpTransport.parse("ftp"));
        assertThrows(IllegalArgumentException.class, () -> McpTransport.parse(""));
    }
}

class McpServerTest {
    @Test
    void create_basic() {
        McpServer s = new McpServer("srv-1", "Test", "x", "http://localhost:9000",
                McpTransport.HTTP_STREAMABLE, "2025-06-18");
        assertEquals("srv-1", s.id());
        assertEquals(McpTransport.HTTP_STREAMABLE, s.transport());
    }
    @Test
    void rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> new McpServer("", "n", "x", "u", null, null));
        assertThrows(IllegalArgumentException.class, () -> new McpServer("id", "", "x", "u", null, null));
        assertThrows(IllegalArgumentException.class, () -> new McpServer("id", "n", "x", "", null, null));
    }
    @Test
    void defaultsTransportAndProtocolVersion() {
        McpServer s = new McpServer("id", "n", null, "u", null, null);
        assertEquals(McpTransport.HTTP_STREAMABLE, s.transport());
        assertEquals("2025-06-18", s.protocolVersion());
    }
}

class McpToolTest {
    @Test
    void toMap_hasSchema() {
        McpTool t = new McpTool("echo", "echo args", Map.of("type", "object"));
        Map<String, Object> m = t.toMap();
        assertEquals("echo", m.get("name"));
        assertNotNull(m.get("inputSchema"));
    }
    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> new McpTool("", "x", null));
    }
}

class McpToolCallTest {
    @Test
    void toMap_roundTrip() {
        McpToolCall c = new McpToolCall("srv", "tool",
                Map.of("x", 1), "req-1", "sess-1");
        Map<String, Object> m = c.toMap();
        assertEquals("srv", m.get("serverId"));
        assertEquals("req-1", m.get("requestId"));
    }
    @Test
    void rejectsBlankServerOrTool() {
        assertThrows(IllegalArgumentException.class, () -> new McpToolCall("", "t", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new McpToolCall("s", "", null, null, null));
    }
}

class McpToolResultTest {
    @Test
    void text_factoryBuildsTextBlock() {
        McpToolResult r = McpToolResult.text("hello");
        assertFalse(r.isError());
        assertEquals(1, r.content().size());
        assertEquals("text", r.content().get(0).type());
        assertEquals("hello", r.content().get(0).text());
    }
    @Test
    void error_factoryMarksIsError() {
        McpToolResult r = McpToolResult.error("bad input");
        assertTrue(r.isError());
    }
    @Test
    void toMap_containsIsErrorAndContent() {
        McpToolResult r = McpToolResult.text("hi");
        Map<String, Object> m = r.toMap();
        assertEquals(false, m.get("isError"));
        assertTrue(m.get("content") instanceof List);
    }
}

class McpJsonRpcTest {
    @Test
    void success_hasVersionAndId() {
        Map<String, Object> m = McpJsonRpc.success("req-1", Map.of("ok", true));
        assertEquals("2.0", m.get("jsonrpc"));
        assertEquals("req-1", m.get("id"));
        assertNotNull(m.get("result"));
    }
    @Test
    void error_hasCodeAndMessage() {
        Map<String, Object> m = McpJsonRpc.error("req-2", McpJsonRpc.METHOD_NOT_FOUND, "unknown method");
        assertEquals("2.0", m.get("jsonrpc"));
        Map<String, Object> err = (Map<String, Object>) m.get("error");
        assertEquals(McpJsonRpc.METHOD_NOT_FOUND, err.get("code"));
        assertEquals("unknown method", err.get("message"));
    }
    @Test
    void error_codes_defined() {
        assertEquals(-32700, McpJsonRpc.PARSE_ERROR);
        assertEquals(-32600, McpJsonRpc.INVALID_REQUEST);
        assertEquals(-32601, McpJsonRpc.METHOD_NOT_FOUND);
        assertEquals(-32602, McpJsonRpc.INVALID_PARAMS);
        assertEquals(-32603, McpJsonRpc.INTERNAL_ERROR);
    }
}
