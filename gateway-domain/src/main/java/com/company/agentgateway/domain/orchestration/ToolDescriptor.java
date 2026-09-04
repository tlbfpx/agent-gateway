package com.company.agentgateway.domain.orchestration;

/**
 * 工具描述符(domain 视角,供 LLM function calling)。
 * 由 application 层 AgentToolRegistry 从 AgentCard 转换而来(spec §4.2),domain 端口不感知 AgentCard 来源。
 *
 * <p><b>Sprint 2 P1</b>:新增 {@code mutating} 字段 — 标识此 tool 是否变更外部世界
 * (写数据库、调外部 API、扣款等)。ReplayService 在 safeReplay=true 时仅跳过 mutating=true 的 tool。
 */
public record ToolDescriptor(String name, String description, String inputSchemaJson, boolean mutating) {
    public ToolDescriptor(String name, String description, String inputSchemaJson) {
        this(name, description, inputSchemaJson, false);
    }
}