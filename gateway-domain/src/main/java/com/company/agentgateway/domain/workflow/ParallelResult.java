package com.company.agentgateway.domain.workflow;

import java.util.List;
import java.util.Map;

/**
 * Parallel 节点执行结果(spec C2 §3.4):branches 完整列表(含失败)+ 合并 outputs(firstError 决定 overall 状态)。
 */
public record ParallelResult(
        List<StepRun> branches,
        Map<String, Object> outputs,
        long durationMs,
        String firstError) {

    /** 全局状态(基于 firstError):null → COMPLETED,否则 FAILED。 */
    public StepRun.Status overallStatus() {
        return firstError == null ? StepRun.Status.COMPLETED : StepRun.Status.FAILED;
    }
}