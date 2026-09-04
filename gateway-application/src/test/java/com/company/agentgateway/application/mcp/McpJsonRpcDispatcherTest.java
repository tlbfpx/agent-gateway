package com.company.agentgateway.application.mcp;

import com.company.agentgateway.domain.mcp.McpJsonRpc;
import com.company.agentgateway.infra.persistence.mcp.InMemoryMcpServerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpJsonRpcDispatcherTest {

    private InMemoryMcpServerRepository port;
    private McpJsonRpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        port = new InMemoryMcpServerRepository();
        dispatcher = new McpJsonRpcDispatcher(port);
    }

    @Test
    void initialize_returnsCapabilities() {
        Map<String, Object> resp = dispatcher.dispatch("1", McpJsonRpcDispatcher.METHOD_INITIALIZE, Map.of());
        assertEquals("2.0", resp.get("jsonrpc"));
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        assertEquals(McpJsonRpcDispatcher.PROTOCOL_VERSION, result.get("protocolVersion"));
        Map<String, Object> caps = (Map<String, Object>) result.get("capabilities");
        assertNotNull(caps.get("tools"));
    }

    @Test
    void toolsList_returnsBuiltin() {
        Map<String, Object> resp = dispatcher.dispatch("2", McpJsonRpcDispatcher.METHOD_TOOLS_LIST,
                Map.of("serverId", "builtin-echo"));
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertTrue(tools.stream().anyMatch(t -> "echo".equals(t.get("name"))));
    }

    @Test
    void toolsList_unknownServer() {
        Map<String, Object> resp = dispatcher.dispatch("3", McpJsonRpcDispatcher.METHOD_TOOLS_LIST,
                Map.of("serverId", "nope"));
        Map<String, Object> err = (Map<String, Object>) resp.get("error");
        assertEquals(McpJsonRpc.INVALID_PARAMS, err.get("code"));
    }

    @Test
    void toolsCall_echo() {
        Map<String, Object> resp = dispatcher.dispatch("4", McpJsonRpcDispatcher.METHOD_TOOLS_CALL,
                Map.of("serverId", "builtin-echo", "name", "echo", "arguments", Map.of("x", 1)));
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        assertEquals(false, result.get("isError"));
    }

    @Test
    void unknownMethod_returnsNotFound() {
        Map<String, Object> resp = dispatcher.dispatch("5", "nonsense", Map.of());
        Map<String, Object> err = (Map<String, Object>) resp.get("error");
        assertEquals(McpJsonRpc.METHOD_NOT_FOUND, err.get("code"));
    }

    @Test
    void blankMethod_returnsInvalid() {
        Map<String, Object> resp = dispatcher.dispatch("6", "", Map.of());
        Map<String, Object> err = (Map<String, Object>) resp.get("error");
        assertEquals(McpJsonRpc.INVALID_REQUEST, err.get("code"));
    }

    @Test
    void toolsCall_missingName() {
        Map<String, Object> resp = dispatcher.dispatch("7", McpJsonRpcDispatcher.METHOD_TOOLS_CALL,
                Map.of("serverId", "builtin-echo"));
        Map<String, Object> err = (Map<String, Object>) resp.get("error");
        assertEquals(McpJsonRpc.INVALID_PARAMS, err.get("code"));
    }
}
