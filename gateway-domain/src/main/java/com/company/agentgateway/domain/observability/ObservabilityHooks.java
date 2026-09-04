package com.company.agentgateway.domain.observability;

/**
 * 出站端口：可观测埋点（spec §7，SPI）。由 gateway-infra-observability 实现（Micrometer）。
 *
 * <p>贯穿各层的关键点埋点：chat 请求/完成、Agent 调用、token 消耗、错误。
 * 指标带 tenant/user/agent/model/channel 标签（spec §7.2）。
 *
 * <p>实现应快速、不阻塞主链路（如 Micrometer 异步/无锁计数）。
 * 默认 NoopObservabilityHooks（无 Micrometer 时）。
 */
public interface ObservabilityHooks {

    /** chat 请求开始（chat.requests counter +1，记标签）。 */
    void onChatRequest(String tenant, String user, String model, String channel);

    /** chat 完成（chat.latency histogram + chat.errors if !success）。 */
    void onChatComplete(String tenant, String model, long latencyMs, boolean success);

    /** Agent 调用开始（agent.invocations counter +1，命中分布）。 */
    void onAgentInvoke(String tenant, String agentName, String model);

    /** Agent 调用完成（agent.latency + agent.errors if !success）。 */
    void onAgentComplete(String tenant, String agentName, long latencyMs, boolean success);

    /** token 消耗（llm.tokens{in,out}，按 model 标签核算成本）。 */
    void onTokens(String tenant, String model, long tokensIn, long tokensOut);

    /** 错误（按 code 分类，chat.errors / agent.errors）。 */
    void onError(String tenant, String code);

    /** 工作流运行完成(spec C1 §6 + A 体系):发布 workflow.run.duration + count / failed。 */
    void onWorkflowComplete(String tenant, String workflowName, String runId,
                             long durationMs, boolean success);

    /** Noop 实现（无 Micrometer 时的默认，无开销）。 */
    ObservabilityHooks NOOP = new ObservabilityHooks() {
        @Override public void onChatRequest(String t, String u, String m, String c) {}
        @Override public void onChatComplete(String t, String m, long l, boolean s) {}
        @Override public void onAgentInvoke(String t, String a, String m) {}
        @Override public void onAgentComplete(String t, String a, long l, boolean s) {}
        @Override public void onTokens(String t, String m, long ti, long to) {}
        @Override public void onError(String t, String c) {}
        @Override public void onWorkflowComplete(String t, String w, String r, long l, boolean s) {}
    };
}
