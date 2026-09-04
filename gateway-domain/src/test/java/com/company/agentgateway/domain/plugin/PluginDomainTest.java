package com.company.agentgateway.domain.plugin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginCapabilityTest {
    @Test
    void enumHas6Values() {
        assertEquals(6, PluginCapability.values().length);
    }
}

class PluginDescriptorTest {
    @Test
    void create_basic() {
        PluginDescriptor d = new PluginDescriptor(
                "header-inject", "Header Inject", "1.0.0", "x",
                PluginDescriptor.PluginFormat.JAVA,
                Set.of(PluginCapability.HEADER_INJECT), List.of("core"), true);
        assertEquals("header-inject", d.id());
        assertTrue(d.builtin());
    }
    @Test
    void rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> new PluginDescriptor(
                "", "n", "1.0", "x", null, null, null, false));
        assertThrows(IllegalArgumentException.class, () -> new PluginDescriptor(
                "id", "", "1.0", "x", null, null, null, false));
    }
    @Test
    void toMap_serializesCapabilities() {
        PluginDescriptor d = new PluginDescriptor(
                "id", "n", "1.0", "x", null,
                Set.of(PluginCapability.AUDIT, PluginCapability.LOG), null, false);
        Map<String, Object> m = d.toMap();
        List<String> caps = new java.util.ArrayList<>((List<String>) m.get("capabilities"));
        // Set.of 迭代顺序不确定(JEP 269),比较排序后内容
        java.util.Collections.sort(caps);
        assertEquals(List.of("AUDIT", "LOG"), caps);
    }
}

class PluginRequestTest {
    @Test
    void create_defaults() {
        PluginRequest r = new PluginRequest(null, null, null, null, null, null);
        assertEquals("", r.path());
        assertEquals("GET", r.method());
        assertEquals("default", r.tenant());
    }
}

class PluginResponseTest {
    @Test
    void passthrough_returns200() {
        PluginResponse r = PluginResponse.passthrough();
        assertEquals(200, r.status());
        assertFalse(r.blocked());
    }
    @Test
    void blocked_returns429() {
        PluginResponse r = PluginResponse.blocked("too fast");
        assertEquals(429, r.status());
        assertTrue(r.blocked());
        assertNotNull(r.blockReason());
    }
}