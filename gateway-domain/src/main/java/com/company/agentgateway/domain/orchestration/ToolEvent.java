package com.company.agentgateway.domain.orchestration;

/** 工具调用流式事件。sealed 穷尽。 */
public sealed interface ToolEvent
        permits ToolEvent.Delta, ToolEvent.Complete, ToolEvent.Error {
    record Delta(String content) implements ToolEvent {}
    record Complete(String fullResult) implements ToolEvent {}
    record Error(String code, String message) implements ToolEvent {}
}
