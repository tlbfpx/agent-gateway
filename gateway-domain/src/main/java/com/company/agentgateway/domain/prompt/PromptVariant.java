package com.company.agentgateway.domain.prompt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A/B 实验中的单个变体（spec 2026-09-02 §prompt-version §4.1）。
 *
 * <p>由 {@code PromptExperiment.variants} 持有;{@code weight} 是 0-100 的整数,
 * 一个实验的所有 variant 权重之和必须 == 100（{@code ABTestService} 创建时校验）。
 *
 * <p>{@code versionId} 指向具体的 {@link PromptVersion}。
 */
public record PromptVariant(
        long versionId,
        int weight,
        String label) {

    public PromptVariant {
        if (versionId <= 0) {
            throw new IllegalArgumentException("versionId must be > 0, got " + versionId);
        }
        if (weight < 0 || weight > 100) {
            throw new IllegalArgumentException("weight must be 0..100, got " + weight);
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("versionId", versionId);
        m.put("weight", weight);
        m.put("label", label == null ? "" : label);
        return m;
    }
}
