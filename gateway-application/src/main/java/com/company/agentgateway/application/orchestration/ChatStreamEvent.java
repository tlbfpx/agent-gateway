package com.company.agentgateway.application.orchestration;

/**
 * 编排对外流式事件（编排核心对外契约，区别于 domain LlmEvent/ToolEvent）。
 *
 * <p>SSE 端点把本事件映射为 SSE event（spec §8.2）：
 * Delta→chunk, Complete→done, Error→error。ToolCallStarted/Result 供前端显示「调用 X 中/结果」。
 */
public sealed interface ChatStreamEvent
        permits ChatStreamEvent.Delta, ChatStreamEvent.ToolCallStarted,
                ChatStreamEvent.ToolCallResult, ChatStreamEvent.Complete, ChatStreamEvent.Error {

    /** LLM 文本增量（流式输出给用户）。 */
    record Delta(String content) implements ChatStreamEvent {}

    /** 工具（Agent）调用开始（前端可显示「调用 X 中」）。 */
    record ToolCallStarted(String agentName) implements ChatStreamEvent {}

    /** 工具调用结果（前端显示成功/失败）。 */
    record ToolCallResult(String agentName, boolean success) implements ChatStreamEvent {}

    /** 本轮对话完成。fullText = 完整回答文本；meta = 实际命中模型 + token 用量（透明展示用）。 */
    record Complete(String fullText, Meta meta) implements ChatStreamEvent {
        /** 兼容旧构造（无用量信息）。 */
        public Complete(String fullText) {
            this(fullText, null);
        }
    }

    /**
     * 消息级用量元数据（消息角标透明展示）：实际命中的模型（灰度分流后）+ token 用量。
     *
     * <p>tokens 为估算值（chars/4，与限流预扣口径一致）；精确 tokenizer 二期接入。
     *
     * <p>cacheHit：本条回答是否来自提示缓存（Prompt Cache 命中透明展示，流式 done / 非流式响应同源）。
     */
    record Meta(String model, long tokensIn, long tokensOut, boolean cacheHit) {
        /** 兼容旧构造（无缓存信息）。 */
        public Meta(String model, long tokensIn, long tokensOut) {
            this(model, tokensIn, tokensOut, false);
        }
    }

    /** 错误（认证/授权/Agent/LLM/超时等）。 */
    record Error(String code, String message) implements ChatStreamEvent {}
}
