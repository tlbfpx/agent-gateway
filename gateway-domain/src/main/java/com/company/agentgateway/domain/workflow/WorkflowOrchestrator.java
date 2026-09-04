package com.company.agentgateway.domain.workflow;

import com.company.agentgateway.domain.orchestration.InvocationCtx;

import java.util.Map;

/**
 * 工作流编排端口(spec C1 §3.4):同步执行 workflow,返回完整 WorkflowRun。
 * 任一步失败 → WorkflowRun.status=FAILED(后续 step 不执行),由实现层 fail-fast 决定。
 */
public interface WorkflowOrchestrator {

    WorkflowRun run(WorkflowDef def, Map<String, Object> inputs, InvocationCtx ctx);
}