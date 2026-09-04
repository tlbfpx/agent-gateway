package com.company.agentgateway.domain.workflow;

import java.util.Map;

/**
 * Parallel 分支定义(spec C2 §3.1 + C2.2):parallel 节点内的单分支,可独立 agent / inputs / timeout。
 * retryCount:per-branch 重试上限(0 = 不重试;spec C2.2 引入;JoinAll/JoinAny 通用)。
 */
public record BranchDef(
        String name,
        String agent,
        Map<String, JsonPathExpression> inputs,
        Integer timeoutMs,
        Integer retryCount) {

    /** 兼容旧 4 参构造:retryCount 默认 0(不重试)。 */
    public BranchDef(String name, String agent, Map<String, JsonPathExpression> inputs, Integer timeoutMs) {
        this(name, agent, inputs, timeoutMs, 0);
    }
}