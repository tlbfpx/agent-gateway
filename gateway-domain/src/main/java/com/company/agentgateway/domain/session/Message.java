package com.company.agentgateway.domain.session;

/**
 * 会话消息。sealed 保证模式匹配穷尽。schema 用 String（JSON 文本），非 JsonNode（零框架）。
 *
 * <p>子类型 public（各自独立文件）：编排层与持久层需构造消息。
 */
public sealed interface Message
        permits UserMessage, AssistantMessage, ToolCallMessage, ToolResultMessage {}
