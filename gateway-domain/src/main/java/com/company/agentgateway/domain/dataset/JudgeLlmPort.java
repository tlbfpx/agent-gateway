package com.company.agentgateway.domain.dataset;

/**
 * LLM 同步调用端口（spec 2026-09-02 §llm-judge §5）。
 *
 * <p>与 {@link com.company.agentgateway.domain.orchestration.ChatClientPort} 不同：
 * Judge 是同步单次调用,不需要流式或多轮历史。
 * 抽象出来便于 stub 测试 + 接真实 LLM 时只换实现。
 */
public interface JudgeLlmPort {

    /**
     * 同步调用 LLM,返回完整文本响应。
     *
     * @param systemPrompt  角色 + 评分规则
     * @param userPrompt    当前请求(已渲染好的 input/expected/actual)
     * @param model         模型标识(可空,空则用 Port 默认)
     * @param temperature   0..1,默认 0.0(确定性评判)
     * @return LLM 输出文本
     */
    String complete(String systemPrompt, String userPrompt, String model, double temperature);

    /** 默认实现:无 JudgeLlmPort bean 时由 StubJudge 兜底 */
    default boolean isAvailable() { return false; }
}