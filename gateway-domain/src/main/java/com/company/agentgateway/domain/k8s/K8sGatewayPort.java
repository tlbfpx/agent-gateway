package com.company.agentgateway.domain.k8s;

import java.util.List;
import java.util.Optional;

/**
 * K8s Gateway/Route CRD 端口（spec 2026-09-02 §k8s-crd §4）。
 *
 * <p>实现：
 * <ul>
 *   <li>P0：{@code InMemoryK8sGatewayStore} —— 模拟 K8s API server</li>
 *   <li>P1：Fabric8 + Informer</li>
 * </ul>
 */
public interface K8sGatewayPort {

    // ============= Gateway =============

    /** 列 namespace 下所有 Gateway(P0 单 namespace) */
    List<GatewaySpec> listGateways(String namespace);

    /** 按 namespace + name 查 */
    Optional<GatewaySpec> findGateway(String namespace, String name);

    /** 创建/更新(P0 单 namespace;覆盖式) */
    GatewaySpec applyGateway(String namespace, String name, GatewaySpec spec);

    /** 删除 */
    boolean deleteGateway(String namespace, String name);

    // ============= Route =============

    List<RouteSpec> listRoutes(String namespace);

    Optional<RouteSpec> findRoute(String namespace, String name);

    RouteSpec applyRoute(String namespace, String name, RouteSpec spec);

    boolean deleteRoute(String namespace, String name);

    /** 已被 gateway 引用的所有 routes(用于 reconciler) */
    List<RouteSpec> findRoutesByGateway(String namespace, String gatewayRef);
}