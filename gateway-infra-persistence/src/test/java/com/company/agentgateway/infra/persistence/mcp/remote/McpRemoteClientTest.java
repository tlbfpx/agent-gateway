package com.company.agentgateway.infra.persistence.mcp.remote;

import com.company.agentgateway.domain.mcp.McpToolCall;
import com.company.agentgateway.domain.mcp.McpToolResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * R18 #1 McpRemoteClient E2E 测：JDK HttpServer 模拟远端 MCP server。
 */
class McpRemoteClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger callCount = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            callCount.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response = route(body);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** 根据 method 返回不同 JSON-RPC 响应 */
    private String route(String body) {
        if (body.contains("\"method\":\"initialize\"")) {
            return "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{" +
                    "\"protocolVersion\":\"2025-06-18\"," +
                    "\"serverInfo\":{\"name\":\"test-server\",\"version\":\"1.0\"}," +
                    "\"capabilities\":{}}}";
        }
        if (body.contains("\"method\":\"tools/list\"")) {
            return "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{" +
                    "\"server\":{\"id\":\"remote-1\",\"name\":\"Remote Test\"}," +
                    "\"tools\":[{\"name\":\"echo\",\"description\":\"echo\"," +
                    "\"inputSchema\":{\"type\":\"object\"}}]}}";
        }
        if (body.contains("\"method\":\"tools/call\"")) {
            return "{\"jsonrpc\":\"2.0\",\"id\":\"3\",\"result\":{" +
                    "\"isError\":false,\"content\":[{\"type\":\"text\",\"text\":\"hello from remote\"}]}}";
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":\"?\",\"error\":{\"code\":-32601,\"message\":\"unknown method\"}}";
    }

    @Test
    void initialize_returnsServerInfo() throws Exception {
        McpRemoteClient client = new McpRemoteClient();
        Map<String, Object> result = client.initialize(baseUrl, Duration.ofSeconds(2));
        assertEquals("2025-06-18", result.get("protocolVersion"));
        assertEquals(1, callCount.get());
    }

    @Test
    void listTools_returnsToolList() throws Exception {
        McpRemoteClient client = new McpRemoteClient();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) client.listTools(baseUrl, "remote-1", Duration.ofSeconds(2));
        assertNotNull(result);
        assertTrue(result.get("tools") instanceof java.util.List);
        assertEquals(1, callCount.get());  // 1 call (listTools only)
    }

    @Test
    void callTool_returnsResult() throws Exception {
        McpRemoteClient client = new McpRemoteClient();
        McpToolCall call = new McpToolCall("remote-1", "echo",
                Map.of("x", 1), null, null);
        McpToolResult r = client.callTool(baseUrl, call, Duration.ofSeconds(2));
        assertNotNull(r);
        assertEquals(false, r.isError());
        // metadata 包含 raw 响应;内容以 metadata 形式返回(R18+1 解析为 ContentBlock)
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> meta = (java.util.Map<String, Object>) r.metadata();
        java.util.List<java.util.Map<String, Object>> content =
                (java.util.List<java.util.Map<String, Object>>) meta.get("content");
        assertEquals(1, content.size());
        assertEquals("hello from remote", content.get(0).get("text"));
    }

    @Test
    void callTool_error_returnsIsError() throws Exception {
        server.removeContext("/mcp");
        server.createContext("/mcp", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String resp = "{\"jsonrpc\":\"2.0\",\"id\":\"3\",\"error\":{\"code\":-32602,\"message\":\"bad params\"}}";
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        });
        McpRemoteClient client = new McpRemoteClient();
        McpToolCall call = new McpToolCall("remote-1", "echo", Map.of(), null, null);
        McpToolResult r = client.callTool(baseUrl, call, Duration.ofSeconds(2));
        assertTrue(r.isError());
        assertTrue(r.content().get(0).text().contains("bad params"));
    }

    @Test
    void callTool_httpError_throws() {
        McpRemoteClient client = new McpRemoteClient();
        McpToolCall call = new McpToolCall("remote-1", "echo", Map.of(), null, null);
        // 连接到不存在端口 → 抛 IOException
        try {
            client.callTool("http://127.0.0.1:1/mcp", call, Duration.ofMillis(500));
            fail("expected IOException");
        } catch (Exception expected) {
            // ok
        }
    }

    @Test
    void toJson_handlesNestedStructures() {
        String json = McpRemoteClient.toJson(Map.of(
                "a", 1, "b", "x", "c", true, "d", Map.of("e", 2),
                "f", java.util.List.of(1, 2, 3)));
        assertTrue(json.contains("\"a\":1"));
        assertTrue(json.contains("\"b\":\"x\""));
        assertTrue(json.contains("\"c\":true"));
        assertTrue(json.contains("\"e\":2"));
        assertTrue(json.contains("[1,2,3]") || json.contains("[1, 2, 3]"));
    }

    @Test
    void fromJson_parsesComplex() {
        Map<String, Object> m = McpRemoteClient.fromJson(
                "{\"a\":1,\"b\":\"x\",\"c\":true,\"d\":null,\"e\":[1,2],\"f\":{\"k\":\"v\"}}");
        assertEquals(1L, m.get("a"));
        assertEquals("x", m.get("b"));
        assertEquals(Boolean.TRUE, m.get("c"));
        assertEquals(null, m.get("d"));
        assertTrue(m.get("e") instanceof java.util.List);
        @SuppressWarnings("unchecked")
        Map<String, Object> f = (Map<String, Object>) m.get("f");
        assertEquals("v", f.get("k"));
    }
}