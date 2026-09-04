package com.company.agentgateway.domain.dataset;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单条 case 评测结果（spec 2026-09-02 §dataset-eval §3.4）。
 */
public record EvalCaseResult(
        long caseId,
        String actualOutput,
        boolean passed,
        double score,
        long latencyMs) {

    public EvalCaseResult {
        if (caseId <= 0) throw new IllegalArgumentException("caseId must be > 0");
        if (actualOutput == null) actualOutput = "";
        if (score < 0 || score > 1) {
            throw new IllegalArgumentException("score must be 0..1, got " + score);
        }
        if (latencyMs < 0) latencyMs = 0;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseId", caseId);
        m.put("actualOutput", actualOutput);
        m.put("passed", passed);
        m.put("score", score);
        m.put("latencyMs", latencyMs);
        return m;
    }
}
