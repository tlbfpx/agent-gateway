package com.company.agentgateway.interfaces.mcp;

import com.company.agentgateway.application.mcp.McpJsonRpcDispatcher;
import com.company.agentgateway.domain.mcp.McpJsonRpc;
import com.company.agentgateway.infra.persistence.mcp.InMemoryMcpServerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpControllerTest {

    private McpController controller;

    @BeforeEach
    void setUp() {
        InMemoryMcpServerRepository repo = new InMemoryMcpServerRepository();
        controller = new McpController(new McpJsonRpcDispatcher(repo), repo);
    }

    @Test
    void initialize_viaJsonRpc() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", "1");
        body.put("method", "initialize");
        body.put("params", Map.of());

        ResponseEntity<Map<String, Object>> resp = controller.jsonRpc("sk", body);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> result = (Map<String, Object>) resp.getBody().get("result");
        assertEquals(McpJsonRpcDispatcher.PROTOCOL_VERSION, result.get("protocolVersion"));
    }

    @Test
    void toolsList_viaJsonRpc() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", "2");
        body.put("method", "tools/list");
        body.put("params", Map.of("serverId", "builtin-echo"));

        ResponseEntity<Map<String, Object>> resp = controller.jsonRpc("sk", body);
        Map<String, Object> result = (Map<String, Object>) resp.getBody().get("result");
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertTrue(tools.stream().anyMatch(t -> "echo".equals(t.get("name"))));
    }

    @Test
    void toolsCall_echo() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", "3");
        body.put("method", "tools/call");
        body.put("params", Map.of(
                "serverId", "builtin-echo",
                "name", "echo",
                "arguments", Map.of("x", 1)));

        ResponseEntity<Map<String, Object>> resp = controller.jsonRpc("sk", body);
        Map<String, Object> result = (Map<String, Object>) resp.getBody().get("result");
        assertEquals(false, result.get("isError"));
    }

    @Test
    void notification_returns204() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        // no id field
        body.put("method", "tools/list");
        body.put("params", Map.of("serverId", "builtin-echo"));

        ResponseEntity<Map<String, Object>> resp = controller.jsonRpc("sk", body);
        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        assertNull(resp.getBody());
    }

    @Test
    void invalidJsonRpcVersion() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "1.0");
        body.put("id", "1");
        body.put("method", "initialize");

        ResponseEntity<Map<String, Object>> resp = controller.jsonRpc("sk", body);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        Map<String, Object> err = (Map<String, Object>) resp.getBody().get("error");
        assertEquals(McpJsonRpc.INVALID_REQUEST, err.get("code"));
    }

    @Test
    void methodMustBeString() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", "1");
        body.put("method", 42);

        ResponseEntity<Map<String, Object>> resp = controller.jsonRpc("sk", body);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void listServers_returnsBuiltin() {
        List<Map<String, Object>> servers = controller.listServers("sk");
        assertEquals(2, servers.size());
        assertTrue(servers.stream().anyMatch(s -> "builtin-time".equals(s.get("id"))));
    }

    @Test
    void listTools_unknownServer() {
        assertThrows(ResponseStatusException.class,
                () -> controller.listTools("sk", "nope"));
    }

    @Test
    void rejectsMissingApiKey() {
        assertThrows(ResponseStatusException.class,
                () -> controller.jsonRpc(null, Map.of("jsonrpc", "2.0", "id", "1", "method", "initialize")));
    }
}
