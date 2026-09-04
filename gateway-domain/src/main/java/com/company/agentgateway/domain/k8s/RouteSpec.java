package com.company.agentgateway.domain.k8s;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * K8s Route CRD 规格（spec 2026-09-02 §k8s-crd §3.2）。
 *
 * <pre>
 * apiVersion: gateway.agentgateway.io/v1alpha1
 * kind: AgentRoute
 * spec:
 *   gatewayRef: prod-gateway
 *   match:
 *     - path: /v1/chat
 *       method: POST
 *   backends:
 *     - provider: openai
 *       weight: 100
 * </pre>
 */
public record RouteSpec(
        String name,
        String namespace,
        String gatewayRef,
        List<MatchRule> match,
        List<Backend> backends) {

    public record MatchRule(String path, String method) {
        public MatchRule {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("match.path must not be blank");
            }
        }
    }

    public record Backend(String provider, int weight, String model) {
        public Backend {
            if (provider == null || provider.isBlank()) {
                throw new IllegalArgumentException("backend.provider required");
            }
            if (weight <= 0 || weight > 100) {
                throw new IllegalArgumentException("backend.weight must be 1..100, got " + weight);
            }
        }
    }

    public RouteSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (namespace == null || namespace.isBlank()) {
            namespace = "default";
        }
        if (gatewayRef == null || gatewayRef.isBlank()) {
            throw new IllegalArgumentException("gatewayRef required");
        }
        if (match == null || match.isEmpty()) {
            throw new IllegalArgumentException("at least one match rule required");
        }
        if (backends == null || backends.isEmpty()) {
            throw new IllegalArgumentException("at least one backend required");
        }
    }

    public Map<String, Object> toSpecMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gatewayRef", gatewayRef);
        m.put("match", match.stream().map(r -> Map.of(
                "path", r.path(), "method", r.method() == null ? "ANY" : r.method())).toList());
        m.put("backends", backends.stream().map(b -> {
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("provider", b.provider());
            bm.put("weight", b.weight());
            bm.put("model", b.model() == null ? "" : b.model());
            return bm;
        }).toList());
        return m;
    }
}