package com.company.agentgateway.domain.workflow;

/**
 * 工作流执行失败异常(spec C1 §3.4):携带 step 索引与名称,便于失败定位与告警。
 */
public class WorkflowRuntimeException extends RuntimeException {

    private final String workflowName;
    private final String stepName;
    private final int stepIndex;

    public WorkflowRuntimeException(String workflowName, String stepName, int stepIndex, String message) {
        super(message);
        this.workflowName = workflowName;
        this.stepName = stepName;
        this.stepIndex = stepIndex;
    }

    public WorkflowRuntimeException(String workflowName, String stepName, int stepIndex,
                                     String message, Throwable cause) {
        super(message, cause);
        this.workflowName = workflowName;
        this.stepName = stepName;
        this.stepIndex = stepIndex;
    }

    public String workflowName() { return workflowName; }
    public String stepName() { return stepName; }
    public int stepIndex() { return stepIndex; }
}