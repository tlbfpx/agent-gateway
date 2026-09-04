package com.company.agentgateway.infra.persistence.k8s;

import com.company.agentgateway.domain.k8s.GatewaySpec;
import com.company.agentgateway.domain.k8s.K8sGatewayPort;
import com.company.agentgateway.domain.k8s.RouteSpec;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * K8s CRD 模拟实现（spec 2026-09-02 §k8s-crd §4 P0）。
 *
 * <p>用 {@code ConcurrentMap<ns, Map<name, spec>>} 模拟 K8s API server 的 etcd;
 * 单 namespace 内 spec 覆盖式。R15 替换为 Fabric8 KubernetesClient + Informer。
 */
public class InMemoryK8sGatewayStore implements K8sGatewayPort {

    private final ConcurrentMap<String, ConcurrentMap<String, GatewaySpec>> gateways = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, RouteSpec>> routes = new ConcurrentHashMap<>();

    // ============= Gateway =============

    @Override
    public List<GatewaySpec> listGateways(String namespace) {
        var map = gateways.get(namespace);
        if (map == null) return List.of();
        return List.copyOf(map.values());
    }

    @Override
    public Optional<GatewaySpec> findGateway(String namespace, String name) {
        var map = gateways.get(namespace);
        if (map == null) return Optional.empty();
        return Optional.ofNullable(map.get(name));
    }

    @Override
    public GatewaySpec applyGateway(String namespace, String name, GatewaySpec spec) {
        gateways.computeIfAbsent(namespace, k -> new ConcurrentHashMap<>()).put(name, spec);
        return spec;
    }

    @Override
    public boolean deleteGateway(String namespace, String name) {
        var map = gateways.get(namespace);
        if (map == null) return false;
        return map.remove(name) != null;
    }

    // ============= Route =============

    @Override
    public List<RouteSpec> listRoutes(String namespace) {
        var map = routes.get(namespace);
        if (map == null) return List.of();
        return List.copyOf(map.values());
    }

    @Override
    public Optional<RouteSpec> findRoute(String namespace, String name) {
        var map = routes.get(namespace);
        if (map == null) return Optional.empty();
        return Optional.ofNullable(map.get(name));
    }

    @Override
    public RouteSpec applyRoute(String namespace, String name, RouteSpec spec) {
        routes.computeIfAbsent(namespace, k -> new ConcurrentHashMap<>()).put(name, spec);
        return spec;
    }

    @Override
    public boolean deleteRoute(String namespace, String name) {
        var map = routes.get(namespace);
        if (map == null) return false;
        return map.remove(name) != null;
    }

    @Override
    public List<RouteSpec> findRoutesByGateway(String namespace, String gatewayRef) {
        var map = routes.get(namespace);
        if (map == null) return List.of();
        return map.values().stream()
                .filter(r -> gatewayRef.equals(r.gatewayRef()))
                .toList();
    }
}