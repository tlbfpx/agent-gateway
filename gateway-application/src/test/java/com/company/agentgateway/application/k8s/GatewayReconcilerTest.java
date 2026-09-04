package com.company.agentgateway.application.k8s;

import com.company.agentgateway.domain.k8s.GatewaySpec;
import com.company.agentgateway.domain.k8s.RouteSpec;
import com.company.agentgateway.infra.persistence.k8s.InMemoryK8sGatewayStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayReconcilerTest {

    private InMemoryK8sGatewayStore store;
    private GatewayReconciler reconciler;

    @BeforeEach
    void setUp() {
        store = new InMemoryK8sGatewayStore();
        reconciler = new GatewayReconciler(store);
        store.applyGateway("default", "prod-gw",
                new GatewaySpec("prod-gw", "default",
                        List.of(new GatewaySpec.Listener("http", 8080, "HTTP", false)), 1));
    }

    @Test
    void reconcile_returnsListenersAndRoutes() {
        store.applyRoute("default", "chat-route", new RouteSpec(
                "chat-route", "default", "prod-gw",
                List.of(new RouteSpec.MatchRule("/v1/chat", "POST")),
                List.of(new RouteSpec.Backend("openai", 100, "gpt-4o"))));

        ReconcileResult r = reconciler.reconcile("default", "prod-gw");
        assertNotNull(r.gateway());
        assertEquals(1, r.listeners().size());
        assertTrue(r.listeners().containsKey("http"));
        assertEquals(1, r.routes().size());
        assertEquals("chat-route", r.routes().get(0).get("name"));
    }

    @Test
    void reconcile_emptyRoutes() {
        ReconcileResult r = reconciler.reconcile("default", "prod-gw");
        assertEquals(0, r.routes().size());
        assertEquals(1, r.listeners().size());
    }

    @Test
    void reconcile_unknownGateway_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> reconciler.reconcile("default", "no-such-gw"));
    }

    @Test
    void reconcile_filtersByGatewayRef() {
        store.applyRoute("default", "r1", new RouteSpec("r1", "default", "prod-gw",
                List.of(new RouteSpec.MatchRule("/a", null)),
                List.of(new RouteSpec.Backend("p", 50, null))));
        store.applyRoute("default", "r2", new RouteSpec("r2", "default", "OTHER-GW",
                List.of(new RouteSpec.MatchRule("/b", null)),
                List.of(new RouteSpec.Backend("p", 50, null))));

        ReconcileResult r = reconciler.reconcile("default", "prod-gw");
        assertEquals(1, r.routes().size());
    }
}