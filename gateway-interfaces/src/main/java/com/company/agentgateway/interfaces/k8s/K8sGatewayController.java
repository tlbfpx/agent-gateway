package com.company.agentgateway.interfaces.k8s;

import com.company.agentgateway.application.k8s.GatewayReconciler;
import com.company.agentgateway.application.k8s.ReconcileResult;
import com.company.agentgateway.domain.k8s.GatewaySpec;
import com.company.agentgateway.domain.k8s.K8sGatewayPort;
import com.company.agentgateway.domain.k8s.RouteSpec;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * K8s CRD 模拟 controller（spec 2026-09-02 §k8s-crd §6）。
 *
 * <p>端点模拟 K8s API server：
 * <ul>
 *   <li>{@code GET /apis/gateway.agentgateway.io/v1alpha1/namespaces/{ns}/agentgateways}</li>
 *   <li>{@code POST /apis/.../namespaces/{ns}/agentgateways} —— 创建</li>
 *   <li>{@code GET /apis/.../namespaces/{ns}/agentgateways/{name}}</li>
 *   <li>{@code PUT /apis/.../namespaces/{ns}/agentgateways/{name}}</li>
 *   <li>{@code DELETE /apis/.../namespaces/{ns}/agentgateways/{name}}</li>
 *   <li>Route 同上（{@code agentroutes}）</li>
 *   <li>{@code GET /apis/.../namespaces/{ns}/agentgateways/{name}/reconcile} —— 翻译结果</li>
 * </ul>
 */
@RestController
@RequestMapping("/apis/gateway.agentgateway.io/v1alpha1")
public class K8sGatewayController {

    private final K8sGatewayPort port;
    private final GatewayReconciler reconciler;

    public K8sGatewayController(K8sGatewayPort port, GatewayReconciler reconciler) {
        this.port = port;
        this.reconciler = reconciler;
    }

    // ============= Gateway =============

    @GetMapping("/namespaces/{namespace}/agentgateways")
    public Map<String, Object> listGateways(@PathVariable String namespace) {
        List<Map<String, Object>> items = port.listGateways(namespace).stream()
                .map(this::wrap).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apiVersion", "gateway.agentgateway.io/v1alpha1");
        out.put("kind", "AgentGatewayList");
        out.put("items", items);
        return out;
    }

    @PostMapping("/namespaces/{namespace}/agentgateways")
    public ResponseEntity<Map<String, Object>> createGateway(
            @PathVariable String namespace,
            @RequestBody Map<String, Object> body) {
        GatewaySpec spec = parseGatewayBody(body);
        GatewaySpec saved = port.applyGateway(namespace, spec.name(), spec);
        return ResponseEntity.status(HttpStatus.CREATED).body(wrap(saved));
    }

    @GetMapping("/namespaces/{namespace}/agentgateways/{name}")
    public Map<String, Object> getGateway(
            @PathVariable String namespace,
            @PathVariable String name) {
        return port.findGateway(namespace, name)
                .map(this::wrap)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "gateway not found"));
    }

    @PutMapping("/namespaces/{namespace}/agentgateways/{name}")
    public Map<String, Object> updateGateway(
            @PathVariable String namespace,
            @PathVariable String name,
            @RequestBody Map<String, Object> body) {
        GatewaySpec spec = parseGatewayBody(body);
        spec = new GatewaySpec(name, namespace, spec.listeners(), spec.replicas());
        port.applyGateway(namespace, name, spec);
        return wrap(spec);
    }

    @DeleteMapping("/namespaces/{namespace}/agentgateways/{name}")
    public Map<String, Object> deleteGateway(
            @PathVariable String namespace,
            @PathVariable String name) {
        boolean ok = port.deleteGateway(namespace, name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", ok);
        out.put("name", name);
        return out;
    }

    @GetMapping("/namespaces/{namespace}/agentgateways/{name}/reconcile")
    public ReconcileResult reconcileGateway(
            @PathVariable String namespace,
            @PathVariable String name) {
        return reconciler.reconcile(namespace, name);
    }

    // ============= Route =============

    @GetMapping("/namespaces/{namespace}/agentroutes")
    public Map<String, Object> listRoutes(@PathVariable String namespace) {
        List<Map<String, Object>> items = port.listRoutes(namespace).stream()
                .map(this::wrapRoute).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apiVersion", "gateway.agentgateway.io/v1alpha1");
        out.put("kind", "AgentRouteList");
        out.put("items", items);
        return out;
    }

    @PostMapping("/namespaces/{namespace}/agentroutes")
    public ResponseEntity<Map<String, Object>> createRoute(
            @PathVariable String namespace,
            @RequestBody Map<String, Object> body) {
        RouteSpec spec = parseRouteBody(body);
        RouteSpec persisted = port.applyRoute(namespace, spec.name(), spec);
        return ResponseEntity.status(HttpStatus.CREATED).body(wrapRoute(persisted));
    }

    @DeleteMapping("/namespaces/{namespace}/agentroutes/{name}")
    public Map<String, Object> deleteRoute(
            @PathVariable String namespace,
            @PathVariable String name) {
        boolean ok = port.deleteRoute(namespace, name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", ok);
        out.put("name", name);
        return out;
    }

    // ============= helpers =============

    private Map<String, Object> wrap(GatewaySpec spec) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apiVersion", "gateway.agentgateway.io/v1alpha1");
        out.put("kind", "AgentGateway");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", spec.name());
        meta.put("namespace", spec.namespace());
        out.put("metadata", meta);
        out.put("spec", spec.toSpecMap());
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("conditions", List.of(Map.of("type", "Ready", "status", "True")));
        out.put("status", status);
        return out;
    }

    private Map<String, Object> wrapRoute(RouteSpec spec) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apiVersion", "gateway.agentgateway.io/v1alpha1");
        out.put("kind", "AgentRoute");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", spec.name());
        meta.put("namespace", spec.namespace());
        out.put("metadata", meta);
        out.put("spec", spec.toSpecMap());
        return out;
    }

    @SuppressWarnings("unchecked")
    private GatewaySpec parseGatewayBody(Map<String, Object> body) {
        Map<String, Object> meta = (Map<String, Object>) body.getOrDefault("metadata", Map.of());
        String name = stringOrThrow(meta, "name", "metadata.name required");
        Map<String, Object> spec = (Map<String, Object>) body.getOrDefault("spec", Map.of());
        List<Map<String, Object>> listenersRaw = (List<Map<String, Object>>) spec.get("listeners");
        if (listenersRaw == null || listenersRaw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "spec.listeners required");
        }
        List<GatewaySpec.Listener> listeners = listenersRaw.stream().map(l -> {
            String ln = (String) l.getOrDefault("name", "");
            int port = ((Number) l.getOrDefault("port", 80)).intValue();
            String proto = (String) l.getOrDefault("protocol", "HTTP");
            boolean tls = Boolean.TRUE.equals(l.get("tls"));
            return new GatewaySpec.Listener(ln, port, proto, tls);
        }).toList();
        int replicas = ((Number) spec.getOrDefault("replicas", 1)).intValue();
        return new GatewaySpec(name, "default", listeners, replicas);
    }

    @SuppressWarnings("unchecked")
    private RouteSpec parseRouteBody(Map<String, Object> body) {
        Map<String, Object> meta = (Map<String, Object>) body.getOrDefault("metadata", Map.of());
        String name = stringOrThrow(meta, "name", "metadata.name required");
        Map<String, Object> spec = (Map<String, Object>) body.getOrDefault("spec", Map.of());
        String gatewayRef = stringOrThrow(spec, "gatewayRef", "spec.gatewayRef required");
        List<Map<String, Object>> matchRaw = (List<Map<String, Object>>) spec.get("match");
        if (matchRaw == null || matchRaw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "spec.match required");
        }
        List<RouteSpec.MatchRule> match = matchRaw.stream().map(m ->
                new RouteSpec.MatchRule(
                        (String) m.getOrDefault("path", ""),
                        (String) m.get("method"))).toList();
        List<Map<String, Object>> backRaw = (List<Map<String, Object>>) spec.get("backends");
        if (backRaw == null || backRaw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "spec.backends required");
        }
        List<RouteSpec.Backend> backends = backRaw.stream().map(b ->
                new RouteSpec.Backend(
                        (String) b.getOrDefault("provider", ""),
                        ((Number) b.getOrDefault("weight", 100)).intValue(),
                        (String) b.get("model"))).toList();
        return new RouteSpec(name, "default", gatewayRef, match, backends);
    }

    private static String stringOrThrow(Map<String, Object> body, String key, String msg) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        }
        return v.toString();
    }
}