package com.company.agentgateway.domain.workflow;

import java.util.List;
import java.util.Map;

/**
 * 显式多 Agent 工作流定义(spec C1 §3.1 + C2 扩展):业务方用 YAML/JSON 描述 Step 链 / parallel。
 * 与现有 ChatOrchestrator.runToolLoop(LLM 自决)并行存在;独立入口 POST /v1/workflows/run。
 *
 * <p>steps 元素类型 WorkflowStep(sealed):Single | Parallel。Jackson 反序列化由类型字段自动多态。
 */
public record WorkflowDef(
        String name,
        List<WorkflowStep> steps,
        Map<String, Object> defaultInputs) {

    public WorkflowDef {
        steps = steps == null ? List.of() : List.copyOf(steps);
        defaultInputs = defaultInputs == null ? Map.of() : Map.copyOf(defaultInputs);
    }
}