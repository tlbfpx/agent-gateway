package com.company.agentgateway.domain.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 工作流一次执行的完整结果(spec C1 §3.2):新聚合根,独立于 Session。
 *
 * <p>status 流转:RUNNING → (COMPLETED | FAILED)。
 * steps 按执行顺序记录;每步含 inputs 快照、outputs、durationMs、errorMessage(失败时填)。
 */
public record WorkflowRun(
        String runId,
        String workflowName,
 Status status,
        Instant startedAt,
        Instant finishedAt,
        Map<String, Object> outputs,
        List<StepRun> steps) {

    public enum Status { RUNNING, COMPLETED, FAILED }

    public WorkflowRun {
        steps = steps == null ? List.of() : List.copyOf(steps);
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
    }

    public boolean isFinished() {
        return status == Status.COMPLETED || status == Status.FAILED;
    }
}