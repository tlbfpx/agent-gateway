package com.company.agentgateway.domain.session;

/** 工具调用请求：agent 名 + 入参（JSON 文本）。 */
public record ToolCallMessage(String agentName, String argsJson) implements Message {}
