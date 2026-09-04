package com.company.agentgateway.interfaces.k8s;

import com.company.agentgateway.application.k8s.GatewayReconciler;
import com.company.agentgateway.application.k8s.ReconcileResult;
import com.company.agentgateway.infra.persistence.k8s.InMemoryK8sGatewayStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class K8sGatewayControllerTest {

    private K8sGatewayController controller;
    private InMemoryK8sGatewayStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryK8sGatewayStore();
        controller = new K8sGatewayController(store, new GatewayReconciler(store));
    }

    @Test
    void createGateway_returns201WithK8sEnvelope() {
        var resp = controller.createGateway("default", gatewayBody("prod-gw"));
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertEquals("AgentGateway", body.get("kind"));
        assertEquals("gateway.agentgateway.io/v1alpha1", body.get("apiVersion"));
    }

    @Test
    void listGateways_returnsListEnvelope() {
        controller.createGateway("default", gatewayBody("g1"));
        controller.createGateway("default", gatewayBody("g2"));
        Map<String, Object> out = controller.listGateways("default");
        assertEquals("AgentGatewayList", out.get("kind"));
        List<?> items = (List<?>) out.get("items");
        assertEquals(2, items.size());
    }

    @Test
    void getGateway_notFound_returns404() {
        assertThrows(ResponseStatusException.class,
                () -> controller.getGateway("default", "no-such"));
    }

    @Test
    void updateGateway_replacesSpec() {
        controller.createGateway("default", gatewayBody("prod-gw"));
        // 用全新的 body(只换 listener 端口)
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", "prod-gw");
        body.put("metadata", meta);
        Map<String, Object> l1 = new LinkedHashMap<>();
        l1.put("name", "http2");
        l1.put("port", 9090);
        l1.put("protocol", "HTTP");
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("listeners", List.of(l1));
        spec.put("replicas", 2);
        body.put("spec", spec);

        Map<String, Object> out = controller.updateGateway("default", "prod-gw", body);
        assertEquals("prod-gw", ((Map<?, ?>) out.get("metadata")).get("name"));
        Map<String, Object> outSpec = (Map<String, Object>) out.get("spec");
        List<Map<String, Object>> ls = (List<Map<String, Object>>) outSpec.get("listeners");
        assertEquals(9090, ls.get(0).get("port"));
    }

    @Test
    void deleteGateway_removes() {
        controller.createGateway("default", gatewayBody("prod-gw"));
        Map<String, Object> out = controller.deleteGateway("default", "prod-gw");
        assertEquals(true, out.get("deleted"));
        assertThrows(ResponseStatusException.class,
                () -> controller.getGateway("default", "prod-gw"));
    }

    @Test
    void createRoute_returns201() {
        var g = controller.createGateway("default", gatewayBody("prod-gw"));
        String gatewayName = (String) ((Map<?, ?>) g.getBody().get("metadata")).get("name");
        Map<String, Object> rb = routeBody(gatewayName);
        var resp = controller.createRoute("default", rb);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertEquals("AgentRoute", resp.getBody().get("kind"));
    }

    @Test
    void reconcile_translatesToSpringShape() {
        var g = controller.createGateway("default", gatewayBody("prod-gw"));
        String gwName = (String) ((Map<?, ?>) g.getBody().get("metadata")).get("name");
        controller.createRoute("default", routeBody(gwName));
        ReconcileResult r = controller.reconcileGateway("default", gwName);
        assertNotNull(r);
        assertEquals(1, r.listeners().size());
        assertEquals(1, r.routes().size());
    }

    @Test
    void createGateway_missingName_returns400() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("spec", Map.of("listeners", List.of(
                Map.of("name", "http", "port", 80))));
        assertThrows(ResponseStatusException.class,
                () -> controller.createGateway("default", bad));
    }

    private static Map<String, Object> gatewayBody(String name) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name);
        Map<String, Object> l1 = new LinkedHashMap<>();
        l1.put("name", "http");
        l1.put("port", 8080);
        l1.put("protocol", "HTTP");
        l1.put("tls", false);
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("listeners", List.of(l1));
        spec.put("replicas", 1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metadata", meta);
        body.put("spec", spec);
        return body;
    }

    private static Map<String, Object> routeBody(String gatewayRef) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", "chat-route");
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("gatewayRef", gatewayRef);
        spec.put("match", List.of(Map.of("path", "/v1/chat", "method", "POST")));
        spec.put("backends", List.of(Map.of("provider", "openai", "weight", 100, "model", "gpt-4o")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metadata", meta);
        body.put("spec", spec);
        return body;
    }
}