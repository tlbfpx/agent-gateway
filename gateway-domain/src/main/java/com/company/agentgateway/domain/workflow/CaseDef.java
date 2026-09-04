package com.company.agentgateway.domain.workflow;

/**
 * Switch case(spec C3+C4):value 字面量 + WorkflowStep 节点(可嵌套 Single/Parallel/Switch)。
 * 匹配语义:Object.equals(value)(字符串/数字/布尔直接比较)。
 */
public record CaseDef(Object value, WorkflowStep step) {
}