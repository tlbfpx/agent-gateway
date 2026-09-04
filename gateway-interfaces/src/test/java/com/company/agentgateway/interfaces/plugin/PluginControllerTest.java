package com.company.agentgateway.interfaces.plugin;

import com.company.agentgateway.application.plugin.PluginManager;
import com.company.agentgateway.application.plugin.PluginSandbox;
import com.company.agentgateway.application.plugin.builtin.AuditPlugin;
import com.company.agentgateway.application.plugin.builtin.HeaderInjectPlugin;
import com.company.agentgateway.infra.persistence.plugin.InMemoryPluginRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginControllerTest {

    private PluginController controller;
    private InMemoryPluginRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InMemoryPluginRegistry();
        PluginSandbox sandbox = new PluginSandbox(registry);
        PluginManager manager = new PluginManager(registry);
        controller = new PluginController(registry, sandbox, manager);
    }

    @Test
    void listPlugins_empty() {
        List<Map<String, Object>> got = controller.listPlugins();
        assertEquals(0, got.size());
    }

    @Test
    void listPlugins_returnsBuiltins() {
        registry.register(new HeaderInjectPlugin());
        registry.register(new AuditPlugin());
        List<Map<String, Object>> got = controller.listPlugins();
        assertEquals(2, got.size());
        assertTrue(got.stream().anyMatch(p -> "builtin-header-inject".equals(p.get("id"))));
    }

    @Test
    void getPlugin_unknown_throws404() {
        assertThrows(ResponseStatusException.class, () -> controller.getPlugin("nope"));
    }

    @Test
    void getPlugin_returnsDescriptor() {
        registry.register(new HeaderInjectPlugin());
        Map<String, Object> got = controller.getPlugin("builtin-header-inject");
        assertEquals("builtin-header-inject", got.get("id"));
        assertTrue(((List<?>) got.get("capabilities")).contains("HEADER_INJECT"));
    }

    @Test
    void testSandbox_runsChain() {
        registry.register(new HeaderInjectPlugin());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", "/v1/x");
        body.put("method", "GET");
        body.put("tenant", "au");
        Map<String, Object> got = controller.testSandbox("sk", body);
        assertEquals(200, got.get("status"));
        Map<?, ?> headers = (Map<?, ?>) got.get("headers");
        assertEquals("agent-gateway", headers.get("X-Gateway"));
    }

    @Test
    void disablePlugin_removesFromRegistry() {
        registry.register(new HeaderInjectPlugin());
        Map<String, Object> out = controller.disablePlugin("builtin-header-inject");
        assertEquals(true, out.get("disabled"));
        assertThrows(ResponseStatusException.class, () -> controller.getPlugin("builtin-header-inject"));
    }

    @Test
    void disablePlugin_notFound_throws404() {
        assertThrows(ResponseStatusException.class, () -> controller.disablePlugin("nope"));
    }

    @Test
    void reload_returnsCount() {
        Map<String, Object> out = controller.reload();
        assertEquals(true, out.get("reloaded"));
        assertTrue(((Number) out.get("total")).intValue() >= 0);
    }
}