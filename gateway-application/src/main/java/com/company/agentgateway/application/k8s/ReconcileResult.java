package com.company.agentgateway.application.k8s;

import com.company.agentgateway.domain.k8s.GatewaySpec;

import java.util.List;
import java.util.Map;

/** Reconciler 输出产物。 */
public record ReconcileResult(
        GatewaySpec gateway,
        Map<String, Object> listeners,
        List<Map<String, Object>> routes) {}