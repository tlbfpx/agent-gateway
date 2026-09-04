package com.company.agentgateway.domain.workflow;

import com.company.agentgateway.domain.orchestration.InvocationCtx;

import java.util.Map;

/**
 * Step 节点执行端口(spec C2 §3.4):单步(C1)与并行(C2)统一抽象,WorkflowOrchestratorImpl 按节点类型 dispatch。
 */
public interface WorkflowStepExecutor {

    /** C1:执行单步(返回 StepRun,默认 parent/branch 字段 null)。 */
    StepRun executeSingle(InvocationCtx ctx, StepDef step, Map<String, Object> inputs,
                          String workflowName, int stepIndex);

    /** C2:执行并行节点(返回 ParallelResult 含分支配 StepRun + 合并 outputs)。 */
    ParallelResult executeParallel(InvocationCtx ctx, ParallelDef parallel,
                                    Map<String, Object> inputs,
                                    String workflowName, int stepIndex);

    /** C3:执行 switch 节点(返回选中的 case StepRun + matchedCaseName;defaultCase 必填)。 */
    SwitchResult executeSwitch(InvocationCtx ctx, SwitchDef switchDef,
                                 Map<String, Object> inputs,
                                 String workflowName, int stepIndex);
}