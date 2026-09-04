package com.company.agentgateway.domain.session;

/** 助手（LLM）消息。 */
public record AssistantMessage(String content) implements Message {}
