package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.Authenticator;
import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.orchestration.AgentCardPort;
import com.company.agentgateway.domain.registry.AgentCard;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * RBAC 权限预览端点（spec §19.3）· legacy。
 * POST /v1/admin/discovery/preview：给定 API Key，返回该 principal 能调的 Agent + 能用的模型。
 *
 * <p><b>D2 顺手修复</b>：原路径 {@code POST /v1/admin/rbac/preview} 与 D1
 * {@link AdminRbacPreviewController} 撞路径（bootstrap 上下文 Ambiguous mapping 启动失败）。
 * 本 legacy 端点迁移至 {@code /v1/admin/discovery/preview} + Deprecation 头
 * （沿用 AdminPolicyController 旧端点处理模式）；二期清理删除。
 */
@RestController
@RequestMapping("/v1/admin/discovery")
public class AdminDiscoveryController {

    private final Authenticator authenticator;
    private final AuthorizationService authorizationService;
    private final AgentCardPort agentCardPort;

    public AdminDiscoveryController(Authenticator authenticator,
                                    AuthorizationService authorizationService,
                                    AgentCardPort agentCardPort) {
        this.authenticator = authenticator;
        this.authorizationService = authorizationService;
        this.agentCardPort = agentCardPort;
    }

    /** 权限预览（legacy）：给定 API Key，返回能调的 Agent + 能用的模型。 */
    @Deprecated
    @PostMapping("/preview")
    public org.springframework.http.ResponseEntity<Map<String, Object>> preview(
            @RequestHeader("X-API-Key") String apiKey) {
        AuthPrincipal principal = authenticator.authenticate(apiKey);
        List<String> allowedAgents = agentCardPort.snapshot().stream()
                .filter(card -> authorizationService.canInvokeAgent(principal, card.name()))
                .map(AgentCard::name)
                .toList();
        List<String> allowedModels = principal.allowedModels().stream()
                .map(m -> m.value())
                .toList();
        return org.springframework.http.ResponseEntity.ok()
                .header("Deprecation", "true")
                .body(Map.of(
                        "user", principal.user().value(),
                        "tenant", principal.tenant().value(),
                        "allowedAgents", allowedAgents,
                        "allowedModels", allowedModels));
    }
}
