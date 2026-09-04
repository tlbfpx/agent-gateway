package com.company.agentgateway.domain.k8s;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewaySpecTest {
    @Test
    void create_basic() {
        GatewaySpec g = new GatewaySpec("prod-gw", "default",
                List.of(new GatewaySpec.Listener("http", 8080, "HTTP", false)), 1);
        assertEquals("prod-gw", g.name());
        assertEquals(1, g.replicas());
        assertEquals(1, g.listeners().size());
    }
    @Test
    void rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> new GatewaySpec(
                "", "default", List.of(new GatewaySpec.Listener("x", 80, "HTTP", false)), 1));
        assertThrows(IllegalArgumentException.class, () -> new GatewaySpec(
                "n", "default", List.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> new GatewaySpec(
                "n", "default", List.of(new GatewaySpec.Listener("x", 0, "HTTP", false)), 1));
    }
    @Test
    void toSpecMap_roundTrip() {
        GatewaySpec g = new GatewaySpec("g", "ns",
                List.of(new GatewaySpec.Listener("http", 8080, "HTTP", false)), 2);
        Map<String, Object> m = g.toSpecMap();
        assertEquals(2, m.get("replicas"));
        assertTrue(m.get("listeners") instanceof List);
    }
    @Test
    void defaultsNamespaceAndReplicas() {
        GatewaySpec g = new GatewaySpec("n", "",
                List.of(new GatewaySpec.Listener("x", 80, null, false)), 0);
        assertEquals("default", g.namespace());
        assertEquals(1, g.replicas());
    }
}

class RouteSpecTest {
    @Test
    void create_basic() {
        RouteSpec r = new RouteSpec("chat-route", "default", "prod-gw",
                List.of(new RouteSpec.MatchRule("/v1/chat", "POST")),
                List.of(new RouteSpec.Backend("openai", 100, "gpt-4o")));
        assertEquals("prod-gw", r.gatewayRef());
        assertEquals(1, r.backends().size());
    }
    @Test
    void rejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> new RouteSpec(
                "", "ns", "gw", List.of(new RouteSpec.MatchRule("/a", null)),
                List.of(new RouteSpec.Backend("p", 50, null))));
        assertThrows(IllegalArgumentException.class, () -> new RouteSpec(
                "n", "ns", "", List.of(new RouteSpec.MatchRule("/a", null)),
                List.of(new RouteSpec.Backend("p", 50, null))));
        assertThrows(IllegalArgumentException.class, () -> new RouteSpec(
                "n", "ns", "gw", List.of(), List.of(new RouteSpec.Backend("p", 50, null))));
        assertThrows(IllegalArgumentException.class, () -> new RouteSpec(
                "n", "ns", "gw", List.of(new RouteSpec.MatchRule("/a", null)), List.of()));
    }
    @Test
    void toSpecMap_roundTrip() {
        RouteSpec r = new RouteSpec("r", "ns", "gw",
                List.of(new RouteSpec.MatchRule("/p", "GET")),
                List.of(new RouteSpec.Backend("openai", 80, "gpt-4o")));
        Map<String, Object> m = r.toSpecMap();
        assertEquals("gw", m.get("gatewayRef"));
        assertNotNull(m.get("match"));
    }
}