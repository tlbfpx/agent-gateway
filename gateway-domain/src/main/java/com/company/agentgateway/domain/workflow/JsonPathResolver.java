package com.company.agentgateway.domain.workflow;

import java.util.Map;

/**
 * JSONPath 解析端口(spec C1 §3.4):infra 层提供 jayway 实现。
 *
 * <p>支持的路径(spec C1 §3.1):
 * <ul>
 *   <li>$.inputs.<key> — 顶层输入字段</li>
 *   <li>$.steps.<stepName>.outputs.<key> — 上一步输出</li>
 * </ul>
 */
public interface JsonPathResolver {

    /**
     * @throws WorkflowRuntimeException 当路径无法解析(引用缺失或语法错)
     */
    Object resolve(String jsonPath, Map<String, Object> context);
}