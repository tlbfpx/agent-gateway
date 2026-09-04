package com.company.agentgateway.interfaces.chat;

import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.Authenticator;
import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.orchestration.AgentCardPort;
import com.company.agentgateway.domain.registry.AgentCard;
import com.company.agentgateway.infra.llm.model.ModelRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Agent 目录 + 模型列表端点（spec §23.2）。
 * <ul>
 *   <li>GET /v1/agents：Agent 目录（AgentCardPort.snapshot，按授权过滤——principal 能调的）</li>
 *   <li>GET /v1/models：模型列表（管理员经 /v1/admin/models 配置的全部启用模型——
 *       模型治理归管理员，用户在对话框直接选用）</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1")
public class DiscoveryApiController {

    private final AgentCardPort agentCardPort;
    private final Authenticator authenticator;
    private final AuthorizationService authorizationService;
    private final ModelRegistry modelRegistry;

    public DiscoveryApiController(AgentCardPort agentCardPort, Authenticator authenticator,
                                  AuthorizationService authorizationService,
                                  ModelRegistry modelRegistry) {
        this.agentCardPort = agentCardPort;
        this.authenticator = authenticator;
        this.authorizationService = authorizationService;
        this.modelRegistry = modelRegistry;
    }

    @GetMapping("/agents")
    public List<Map<String, Object>> agents(@RequestHeader("X-API-Key") String apiKey) {
        AuthPrincipal principal = authenticator.authenticate(apiKey);
        return agentCardPort.snapshot().stream()
                .filter(card -> authorizationService.canInvokeAgent(principal, card.name()))
                // Skill 级 RBAC（spec §6.3 二期）：按 grant 过滤可见 skills；全过滤则整个 Agent 隐藏
                .filter(card -> !skillFilteredEmpty(principal, card))
                .map(card -> cardToMap(card, filteredSkills(principal, card)))
                .toList();
    }

    /** 用户对该 Agent 授权的 skills（空 grant = Agent 全部 skills）。 */
    private List<String> filteredSkills(AuthPrincipal principal, com.company.agentgateway.domain.registry.AgentCard card) {
        List<String> skills = card.skills();
        return principal.agentGrants().stream()
                .filter(g -> g.agentName().equals(card.name()))
                .findFirst()
                .<List<String>>map(g -> new java.util.ArrayList<>(g.filterSkills(java.util.Set.copyOf(skills))))
                .orElse(new java.util.ArrayList<>(skills));
    }

    private boolean skillFilteredEmpty(AuthPrincipal principal, com.company.agentgateway.domain.registry.AgentCard card) {
        return !card.skills().isEmpty() && filteredSkills(principal, card).isEmpty();
    }

    /** 模型列表：管理员配置的全部启用模型（displayName + id），用户直接选。 */
    @GetMapping("/models")
    public List<Map<String, Object>> models(@RequestHeader("X-API-Key") String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) authenticator.authenticate(apiKey);
        return modelRegistry.listModels().stream()
                .filter(m -> m.enabled())
                .map(m -> Map.<String, Object>of(
                        "modelId", m.id().value(),
                        "displayName", m.displayName(),
                        "provider", m.provider()))
                .toList();
    }

    private static Map<String, Object> cardToMap(AgentCard card, List<String> skills) {
        return Map.of(
                "name", card.name(),
                "description", card.description(),
                "skills", skills,
                "version", card.version(),
                "available", card.available());
    }
}
