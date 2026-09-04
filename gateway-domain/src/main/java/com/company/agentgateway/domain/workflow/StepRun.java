package com.company.agentgateway.domain.workflow;

import java.util.Map;

/**
 * 单步执行结果(spec C1 §3.2 + C2 扩展):inputs 快照(便于审计/重试)、outputs(供后续 step 引用)。
 *
 * <p>C2 扩展字段(spec §3.3):parentIndex / branchIndex / branchName 用于 parallel 节点内的兄弟节点定位,
 * 不进 PG(本期 in-memory 标记足够,后续如需可迁)。null for non-parallel(单步/Chain 节点)。
 */
public record StepRun(
        String name,
 Status status,
        Map<String, Object> inputs,
        Map<String, Object> outputs,
        Long durationMs,
        String errorMessage,
        Integer parentIndex,
        Integer branchIndex,
        String branchName) {

    public enum Status { RUNNING, COMPLETED, FAILED }

    public StepRun {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
    }

    /** 紧凑构造器(C1 / 非 parallel 路径):parent/branch 字段自动 null,保持向后兼容。 */
    public StepRun(String name, Status status, Map<String, Object> inputs,
                   Map<String, Object> outputs, Long durationMs, String errorMessage) {
        this(name, status, inputs, outputs, durationMs, errorMessage, null, null, null);
    }
}