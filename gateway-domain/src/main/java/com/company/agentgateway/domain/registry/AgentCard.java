package com.company.agentgateway.domain.registry;
import java.util.List;

/**
 * A2A AgentCard 领域视图（spec §3.3 + B 多实例）。
 * inputSchema/outputSchema 为 JSON 文本（String），不引入 Jackson，保持 domain 零框架。
 * 解析为 JsonNode 的工作在 gateway-api/infra 层完成。
 * endpointUrl：远程 Agent 的首选调用地址（A2A invoke 端点；旧契约保留）。
 * endpointUrls：远程 Agent 的全部实例地址（B 多实例负载均衡用）。紧凑构造器把 null/空 endpointUrls
 *             归一化为 List.of(endpointUrl)，保留旧构造调用与单实例语义。
 * ToolPort.invoke 据此发起 A2A 调用，由 ResilientA2aClient 做多实例轮询 + 失败转移。
 *
 * <p><b>Sprint 2 P1</b>:新增 {@code mutating} 字段标记工具是否会变更外部状态;
 * ReplayService.safeReplay=true 时仅跳过 mutating=true 的工具,其他正常执行。
 */
public record AgentCard(String name, String description, List<String> skills,
                        String inputSchema, String outputSchema,
                        String version, boolean available,
                        String endpointUrl,
                        List<String> endpointUrls,
                        boolean mutating) {
    public AgentCard {
        skills = skills == null ? List.of() : List.copyOf(skills);
        endpointUrls = (endpointUrls == null || endpointUrls.isEmpty())
                ? (endpointUrl == null ? List.of() : List.of(endpointUrl))
                : List.copyOf(endpointUrls);
    }

    /** 旧契约便捷构造（保持 8 参形态可读性;mutating 默认 false） */
    public AgentCard(String name, String description, List<String> skills,
                    String inputSchema, String outputSchema,
                    String version, boolean available,
                    String endpointUrl) {
        this(name, description, skills, inputSchema, outputSchema, version, available, endpointUrl, null, false);
    }

    /** 9 参便捷构造(endpointUrls + mutating=false);兼容既有调用 */
    public AgentCard(String name, String description, List<String> skills,
                    String inputSchema, String outputSchema,
                    String version, boolean available,
                    String endpointUrl,
                    List<String> endpointUrls) {
        this(name, description, skills, inputSchema, outputSchema, version, available, endpointUrl, endpointUrls, false);
    }
}