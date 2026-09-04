package com.company.agentgateway.domain.orchestration;

/** LLM 流式事件：增量文本 / 工具调用请求 / 完成。sealed 穷尽。 */
public sealed interface LlmEvent
        permits LlmEvent.Delta, LlmEvent.ToolCall, LlmEvent.Complete {
    record Delta(String content) implements LlmEvent {}

    /**
     * 工具调用请求(spec C1 §3.3):toolCallId 用于原生 ToolResponseMessage 回填(B/C 一并);
     * 老调用点用 2 参紧凑构造器(默认 toolCallId=null)。
     */
    record ToolCall(String toolName, String argsJson, String toolCallId) implements LlmEvent {
        public ToolCall(String toolName, String argsJson) {
            this(toolName, argsJson, null);
        }
    }

    record Complete() implements LlmEvent {}
}
