package com.company.agentgateway.domain.workflow;

import java.util.List;

/**
 * Switch 节点定义(spec C3):基于 key(JSONPath 表达式)在上一步 outputs 取值,顺序匹配 cases;
 * 不匹配走 default(必有,spec 启动校验,无 default 报错)。
 */
public record SwitchDef(
        String name,
        JsonPathExpression key,
        List<CaseDef> cases,
        WorkflowStep defaultStep) {
}