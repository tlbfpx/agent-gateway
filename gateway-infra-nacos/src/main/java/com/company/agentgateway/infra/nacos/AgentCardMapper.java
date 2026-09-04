package com.company.agentgateway.infra.nacos;

import com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo;
import com.alibaba.nacos.api.ai.model.a2a.AgentSkill;

import java.util.List;

/**
 * Nacos A2A AgentCard → domain AgentCard 映射器。
 *
 * <p>字段对应（基于 nacos-client 3.3.0-BETA 实际 API，javap 核对）：
 * <ul>
 *   <li>Nacos AgentCardBasicInfo.getName/getDescription/getVersion → domain name/description/version</li>
 *   <li>Nacos AgentSkill.getName() → domain skills(List&lt;String&gt;)</li>
 *   <li>Nacos AgentCard.getUrl() → domain endpointUrl（A2A 调用地址；null 表示地址未知）</li>
 *   <li>available：detailInfo 非空即视为可用（A2A 注册即在线）</li>
 *   <li>inputSchema/outputSchema：Nacos A2A 模型无 JSON schema 字段（用 skills + url 表达能力），
 *       domain 端填空 "{}"（编排/LLM 工具描述靠 description + skills）</li>
 * </ul>
 *
 * <p><b>多实例(spec B §4.3)</b>:Nacos AgentCard 模型仅暴露单 url,无多实例列表字段。
 * 映射时把 endpointUrls 设为 [endpointUrl](单实例兼容)——Nacos 协议层面的多实例支持下轮再议。
 */
public final class AgentCardMapper {

    private static final String EMPTY_SCHEMA = "{}";

    private AgentCardMapper() {
    }

    /** Nacos AgentCardDetailInfo → domain AgentCard。null 入参返回 null（由调用方决定如何处理）。 */
    public static com.company.agentgateway.domain.registry.AgentCard toDomain(AgentCardDetailInfo nacos) {
        if (nacos == null) {
            return null;
        }
        List<String> skills = mapSkills(nacos.getSkills());
        String endpointUrl = nacos.getUrl();
        return new com.company.agentgateway.domain.registry.AgentCard(
                nacos.getName(),
                nacos.getDescription(),
                skills,
                EMPTY_SCHEMA,
                EMPTY_SCHEMA,
                nacos.getVersion(),
                true,
                endpointUrl,
                // 多实例:Nacos 协议无列表字段,endpointUrls 设为 [endpointUrl](兼容单实例)
                // DevStub 可显式构造 List.of(url1, url2) 测多实例切换
                endpointUrl == null ? null : List.of(endpointUrl)
        );
    }

    private static List<String> mapSkills(List<AgentSkill> nacosSkills) {
        if (nacosSkills == null || nacosSkills.isEmpty()) {
            return List.of();
        }
        return nacosSkills.stream()
                .map(AgentSkill::getName)
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
