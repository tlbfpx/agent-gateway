package com.company.agentgateway.application.plugin;

import com.company.agentgateway.application.plugin.builtin.AuditPlugin;
import com.company.agentgateway.application.plugin.builtin.HeaderInjectPlugin;
import com.company.agentgateway.application.plugin.builtin.RateLimitPlugin;
import com.company.agentgateway.domain.plugin.Plugin;
import com.company.agentgateway.domain.plugin.PluginCapability;
import com.company.agentgateway.domain.plugin.PluginDescriptor;
import com.company.agentgateway.domain.plugin.PluginRegistry;
import com.company.agentgateway.domain.plugin.PluginRequest;
import com.company.agentgateway.domain.plugin.PluginResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginSandboxTest {

    private PluginRegistry registry;
    private PluginSandbox sandbox;

    @BeforeEach
    void setUp() {
        registry = new TestPluginRegistry();
        sandbox = new PluginSandbox(registry);
    }

    @Test
    void emptyRegistry_passthrough() {
        PluginRequest req = new PluginRequest("/v1/x", "GET", Map.of(), "", "au", null);
        PluginResponse r = sandbox.execute(req);
        assertEquals(200, r.status());
        assertFalse(r.blocked());
    }

    @Test
    void headerInject_addsHeader() {
        registry.register(new HeaderInjectPlugin());
        PluginResponse r = sandbox.execute(new PluginRequest("/v1/x", "GET", Map.of(), "", "au", null));
        assertEquals("agent-gateway", r.headers().get("X-Gateway"));
    }

    @Test
    void rateLimit_blocksAfter100Requests() {
        registry.register(new RateLimitPlugin());
        PluginResponse blocked = null;
        for (int i = 0; i <= 100; i++) {
            PluginResponse r = sandbox.execute(
                    new PluginRequest("/v1/x", "GET", Map.of(), "", "au", "sk-test"));
            if (r.blocked()) {
                blocked = r;
                break;
            }
        }
        assertNotNull(blocked, "expected rate limit to trigger after 100 reqs");
        assertEquals(429, blocked.status());
    }

    @Test
    void pluginException_doesNotBlockChain() {
        registry.register(new HeaderInjectPlugin());
        // 注入会抛异常的 plugin
        registry.register(new com.company.agentgateway.domain.plugin.Plugin() {
            @Override public String id() { return "broken"; }
            @Override public com.company.agentgateway.domain.plugin.PluginDescriptor descriptor() {
                return new com.company.agentgateway.domain.plugin.PluginDescriptor(
                        "broken", "Broken", "1.0", "x", null,
                        java.util.Set.of(), java.util.List.of(), false);
            }
            @Override public PluginResponse handle(PluginRequest request) {
                throw new RuntimeException("boom");
            }
        });
        // 不应阻断:应得 200 + X-Gateway 头(HeaderInject 仍执行)
        PluginResponse r = sandbox.execute(new PluginRequest("/x", "GET", Map.of(), "hi", "au", null));
        assertEquals(200, r.status());
        assertEquals("agent-gateway", r.headers().get("X-Gateway"));
    }

    @Test
    void audit_doesNotBlock() {
        registry.register(new AuditPlugin());
        PluginResponse r = sandbox.execute(new PluginRequest("/v1/chat", "POST", Map.of(), "x", "au", "sk-test"));
        assertFalse(r.blocked());
    }

    @Test
    void rateLimit_blocksOthersAfter() {
        registry.register(new RateLimitPlugin());
        registry.register(new HeaderInjectPlugin());
        // 触发限速后,headers 也不会被注入(短路)
        for (int i = 0; i <= 100; i++) {
            sandbox.execute(new PluginRequest("/x", "GET", Map.of(), "", "au", "sk"));
        }
        PluginResponse blocked = sandbox.execute(
                new PluginRequest("/x", "GET", Map.of(), "", "au", "sk"));
        assertTrue(blocked.blocked());
        // 短路:没经过 HeaderInject
        assertEquals(null, blocked.headers().get("X-Gateway"));
    }

    /** 测试用内存 PluginRegistry 简化实现。 */
    static class TestPluginRegistry implements PluginRegistry {
        private final java.util.concurrent.ConcurrentMap<String, Plugin> map = new java.util.concurrent.ConcurrentHashMap<>();
        @Override public Plugin register(Plugin p) { return map.put(p.id(), p); }
        @Override public boolean unregister(String id) { return map.remove(id) != null; }
        @Override public java.util.List<Plugin> listAll() { return java.util.List.copyOf(map.values()); }
        @Override public java.util.Optional<Plugin> findById(String id) { return java.util.Optional.ofNullable(map.get(id)); }
        @Override public java.util.List<Plugin> findByCapability(PluginCapability c) {
            return map.values().stream().filter(p -> p.capabilities().contains(c)).toList();
        }
    }
}