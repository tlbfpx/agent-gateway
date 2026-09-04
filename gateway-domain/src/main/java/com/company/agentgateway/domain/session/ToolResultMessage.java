package com.company.agentgateway.domain.session;

/**
 * 工具调用结果：内容 + 是否被瘦身(spec §5.3)+ 原生 toolCallId(spec C1 §3.3 一并升级 B 推迟项)。
 *
 * <p>toolCallId:Spring AI 协议层要求的 native protocol 对应关系字段;非空时 ChatClientLlmSession
 * 走原生 ToolResponseMessage 回填(严格厂商兼容),为空则文本降级(全厂商兜底)。
 * 旧 InMemory/PG Session 反序列化兼容(默认 null)。
 *
 * <p>紧凑构造器(旧 3 参)保留以兼容现有调用点(ChatOrchestrator / SessionTest / MessageDtoTest 等),
 * toolCallId 默认为 null。强烈建议新代码使用 4 参构造器。
 */
public record ToolResultMessage(String agentName, String content, boolean slimmed, String toolCallId)
        implements Message {

    public ToolResultMessage(String agentName, String content, boolean slimmed) {
        this(agentName, content, slimmed, null);
    }
}