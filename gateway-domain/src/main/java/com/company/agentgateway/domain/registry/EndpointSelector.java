package com.company.agentgateway.domain.registry;

/**
 * Agent 实例选择策略(spec B §4.3)。纯逻辑,可单测。
 */
public interface EndpointSelector {
    /** 从卡片可用的实例列表中选一个(无副作用);空列表返回 null。 */
    String select(AgentCard card);

    /** 标记该 url 失败(用于失败转移时的临时回避,可有可无)。 */
    void onFailure(String url);

    /** 标记该 url 成功(重置其失败计数)。 */
    void onSuccess(String url);
}