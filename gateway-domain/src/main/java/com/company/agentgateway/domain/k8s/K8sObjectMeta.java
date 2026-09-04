package com.company.agentgateway.domain.k8s;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * K8s objectMeta + spec/status 包装（spec 2026-09-02 §k8s-crd §3.3）。
 *
 * <p>完整 K8s object 结构：apiVersion / kind / metadata / spec / status。
 */
public record K8sObjectMeta(
        String apiVersion,
        String kind,
        String name,
        String namespace,
        long generation,
        Map<String, String> labels) {

    public K8sObjectMeta {
        if (apiVersion == null) apiVersion = "gateway.agentgateway.io/v1alpha1";
        if (labels == null) labels = Map.of();
        else labels = Map.copyOf(labels);
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("namespace", namespace == null ? "default" : namespace);
        m.put("generation", generation);
        if (!labels.isEmpty()) m.put("labels", labels);
        return m;
    }
}