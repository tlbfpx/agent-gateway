package com.company.agentgateway.domain.dataset;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次评测运行（spec 2026-09-02 §dataset-eval §3.3）。
 *
 * <p>{@link #status} 状态机：{@code PENDING → RUNNING → COMPLETED|FAILED}。
 * 不可变 record;case 结果存 {@link EvalCaseResult}。
 */
public record EvalRun(
        long id,
        long datasetId,
        long promptVersionId,
        String model,
        EvalStrategy strategy,
        Status status,
        RunMetrics metrics,
        List<EvalCaseResult> results,
        String tenantId,
        long triggeredBy,
        Instant createdAt,
        Instant finishedAt) {

    public enum Status { PENDING, RUNNING, COMPLETED, FAILED }

    public EvalRun {
        if (datasetId <= 0) throw new IllegalArgumentException("datasetId must be > 0");
        if (promptVersionId <= 0) throw new IllegalArgumentException("promptVersionId must be > 0");
        if (model == null || model.isBlank()) throw new IllegalArgumentException("model required");
        if (strategy == null) strategy = EvalStrategy.EXACT;
        if (status == null) status = Status.PENDING;
        results = results == null ? List.of() : List.copyOf(results);
        if (createdAt == null) createdAt = Instant.now();
    }

    /** 聚合指标:case 数 / 通过数 / 总分 / 平均延迟 ms */
    public record RunMetrics(int total, int passed, double passRate, double avgLatencyMs) {
        public static RunMetrics empty() { return new RunMetrics(0, 0, 0.0, 0.0); }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("datasetId", datasetId);
        m.put("promptVersionId", promptVersionId);
        m.put("model", model);
        m.put("strategy", strategy.name());
        m.put("status", status.name());
        m.put("metrics", Map.of(
                "total", metrics.total(),
                "passed", metrics.passed(),
                "passRate", metrics.passRate(),
                "avgLatencyMs", metrics.avgLatencyMs()));
        m.put("tenantId", tenantId);
        m.put("triggeredBy", triggeredBy);
        m.put("createdAt", createdAt.toString());
        m.put("finishedAt", finishedAt == null ? "" : finishedAt.toString());
        m.put("results", results.stream().map(EvalCaseResult::toMap).toList());
        return m;
    }
}
