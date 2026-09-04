package com.company.agentgateway.domain.workflow;

import java.util.Map;

/**
 * 工作流单个 Step 定义(spec C1 §3.1):指定 Agent + inputs(JSONPath 引用运行时上下文)。
 *
 * <p>同一 workflow 内 name 必须唯一(用于 $.steps.<name>.outputs.<key> 引用)。
 * inputs 的 value 是 JSONPath 表达式(以 $ 开头),由 JsonPathResolver 解析。
 */
public record StepDef(
        String name,
        String agent,
        Map<String, JsonPathExpression> inputs,
        Integer timeoutMs) {
}