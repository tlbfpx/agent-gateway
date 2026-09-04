package com.company.agentgateway.infra.persistence;

import com.company.agentgateway.domain.session.AssistantMessage;
import com.company.agentgateway.domain.session.Message;
import com.company.agentgateway.domain.session.ToolCallMessage;
import com.company.agentgateway.domain.session.ToolResultMessage;
import com.company.agentgateway.domain.session.UserMessage;

/**
 * Message 持久化 DTO（design 方案B）：domain Message sealed ↔ 可 Jackson 序列化的 DTO。
 * domain 保持零框架（无 Jackson 注解），序列化形态在 infra 定义。
 *
 * <p>type 字段区分子类型：user / assistant / tool_call / tool_result。
 */
public record MessageDto(String type, String content, String agentName, String argsJson, boolean slimmed) {

    /** domain Message → DTO。 */
    public static MessageDto from(Message m) {
        return switch (m) {
            case UserMessage u -> new MessageDto("user", u.content(), null, null, false);
            case AssistantMessage a -> new MessageDto("assistant", a.content(), null, null, false);
            case ToolCallMessage tc -> new MessageDto("tool_call", null, tc.agentName(), tc.argsJson(), false);
            case ToolResultMessage tr -> new MessageDto("tool_result", tr.content(), tr.agentName(), null, tr.slimmed());
        };
    }

    /** DTO → domain Message。 */
    public Message toDomain() {
        return switch (type) {
            case "user" -> new UserMessage(content);
            case "assistant" -> new AssistantMessage(content);
            case "tool_call" -> new ToolCallMessage(agentName, argsJson);
            case "tool_result" -> new ToolResultMessage(agentName, content, slimmed);
            default -> throw new IllegalArgumentException("Unknown message type: " + type);
        };
    }
}
