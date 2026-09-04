package com.company.agentgateway.application.k8s;

import com.company.agentgateway.domain.k8s.GatewaySpec;
import com.company.agentgateway.domain.k8s.K8sGatewayPort;
import com.company.agentgateway.domain.k8s.RouteSpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway/Route Reconciler（spec 2026-09-02 §k8s-crd §5）。
 *
 * <p>P0 单向:K8s CRD → Spring gateway 配置翻译。
 */
public class GatewayReconciler {

    private static final Logger log = LoggerFactory.getLogger(GatewayReconciler.class);

    private final K8sGatewayPort port;

    public GatewayReconciler(K8sGatewayPort port) {
        this.port = port;
    }

    public ReconcileResult reconcile(String namespace, String gatewayName) {
        GatewaySpec gw = port.findGateway(namespace, gatewayName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "gateway not found: " + namespace + "/" + gatewayName));
        List<RouteSpec> routes = port.findRoutesByGateway(namespace, gatewayName);

        Map<String, Object> listeners = new LinkedHashMap<>();
        for (GatewaySpec.Listener l : gw.listeners()) {
            listeners.put(l.name(), Map.of(
                    "port", l.port(),
                    "protocol", l.protocol(),
                    "tls", l.tls()));
        }

        List<Map<String, Object>> routeTable = routes.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.name());
            m.put("gatewayRef", r.gatewayRef());
            m.put("match", r.match().stream().map(mr -> {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("path", mr.path());
                mm.put("method", mr.method() == null ? "ANY" : mr.method());
                return mm;
            }).toList());
            m.put("backends", r.backends().stream().map(b -> {
                Map<String, Object> bm = new LinkedHashMap<>();
                bm.put("provider", b.provider());
                bm.put("weight", b.weight());
                bm.put("model", b.model() == null ? "" : b.model());
                return bm;
            }).toList());
            return m;
        }).toList();

        log.info("k8s.reconcile ns={} gateway={} routes={}",
                namespace, gatewayName, routes.size());
        return new ReconcileResult(gw, listeners, routeTable);
    }
}