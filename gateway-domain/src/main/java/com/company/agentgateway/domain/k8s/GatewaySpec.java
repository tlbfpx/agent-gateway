package com.company.agentgateway.domain.k8s;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * K8s Gateway CRD 规格（spec 2026-09-02 §k8s-crd §3.1）。
 *
 * <p>对应 YAML：
 * <pre>
 * apiVersion: gateway.agentgateway.io/v1alpha1
 * kind: AgentGateway
 * spec:
 *   listeners:
 *     - name: http
 *       port: 8080
 *       protocol: HTTP
 *       tls: false
 *   replicas: 1
 * </pre>
 */
public record GatewaySpec(
        String name,
        String namespace,
        List<Listener> listeners,
        int replicas) {

    public record Listener(String name, int port, String protocol, boolean tls) {
        public Listener {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("listener.name must not be blank");
            }
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("listener.port must be 1..65535, got " + port);
            }
            if (protocol == null) protocol = "HTTP";
        }
    }

    public GatewaySpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (namespace == null || namespace.isBlank()) {
            namespace = "default";
        }
        if (listeners == null || listeners.isEmpty()) {
            throw new IllegalArgumentException("at least one listener required");
        }
        if (replicas <= 0) replicas = 1;
    }

    public Map<String, Object> toSpecMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("listeners", listeners.stream().map(l -> {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("name", l.name());
            lm.put("port", l.port());
            lm.put("protocol", l.protocol());
            lm.put("tls", l.tls());
            return lm;
        }).toList());
        m.put("replicas", replicas);
        return m;
    }
}