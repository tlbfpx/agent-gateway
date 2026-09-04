package com.company.agentgateway.domain.workflow;

/**
 * Step 节点类型(spec C2 §3.1 + C3):sealed union,代替 WorkflowDef.steps[] 内的 StepDef。
 *
 * <ul>
 *   <li>Single — C1 路径(单 Agent 调用)</li>
 *   <li>Parallel — C2 多源 fan-out(JoinAll/JoinAny)</li>
 *   <li>Switch — C3 分路由(key + 字面量匹配,必 default)</li>
 * </ul>
 */
public sealed interface WorkflowStep {
    record Single(StepDef def) implements WorkflowStep {}
    record Parallel(ParallelDef def) implements WorkflowStep {}
    record Switch(SwitchDef def) implements WorkflowStep {}
}