package com.company.agentgateway.domain.dataset;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 评测用例（spec 2026-09-02 §dataset-eval §3.2）。
 *
 * <p>一条 (input, expectedOutput) + 权重 + 元数据;评测时按 weight 聚合分数。
 */
public record EvalCase(
        long id,
        long datasetId,
        String input,
        String expectedOutput,
        Map<String, Object> metadata,
        int weight) {

    public EvalCase {
        if (datasetId <= 0) {
            throw new IllegalArgumentException("datasetId must be > 0, got " + datasetId);
        }
        if (input == null) input = "";
        if (expectedOutput == null) expectedOutput = "";
        if (weight <= 0) weight = 1;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static EvalCase create(
            long datasetId, String input, String expectedOutput,
            Map<String, Object> metadata, int weight) {
        return new EvalCase(0L, datasetId, input, expectedOutput, metadata, weight);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("datasetId", datasetId);
        m.put("input", input);
        m.put("expectedOutput", expectedOutput);
        m.put("metadata", metadata);
        m.put("weight", weight);
        return m;
    }
}
