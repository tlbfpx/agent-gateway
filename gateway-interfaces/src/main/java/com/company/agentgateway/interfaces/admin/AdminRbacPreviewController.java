package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.iam.PolicyPreview;
import com.company.agentgateway.domain.iam.RoleBindingRepository;
import com.company.agentgateway.domain.iam.RoleQueryService;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RBAC 策略预览（spec §19.3 + §GW-RBAC-011 · D1-2 纯函数决议）。
 *
 * <p>POST {@code /v1/admin/rbac/preview}，body {@code {"userId": "u-1"}}。
 * 复用 {@link RoleQueryService#preview}（domain 纯函数）：不写审计、不上 OTel、
 * 同输入幂等（spec 验收判定：连发 10 次 equals 一致）。
 */
@RestController
@RequestMapping("/v1/admin/rbac")
public class AdminRbacPreviewController {

    private final RoleRepository roleRepository;
    private final RoleBindingRepository roleBindingRepository;
    private final RoleQueryService roleQueryService;

    public AdminRbacPreviewController(RoleRepository roleRepository,
                                      RoleBindingRepository roleBindingRepository,
                                      RoleQueryService roleQueryService) {
        this.roleRepository = roleRepository;
        this.roleBindingRepository = roleBindingRepository;
        this.roleQueryService = roleQueryService;
    }

    /**
     * 兼容两种请求形态：
     * <ul>
     *   <li>{userId}：返回 PolicyPreview（allowedAgents/Models 列表，用户绑定页用）</li>
     *   <li>{actor, action, resource}：四要素判定，返回 {allowed, reason, rule}（RBAC 预览页用）</li>
     * </ul>
     */
    public record PreviewRequest(String userId, String tenantId,
                                 String actor, String action, String resource) {
        /** 兼容旧 2 参形态（仅 userId 查询）。 */
        public PreviewRequest(String userId, String tenantId) {
            this(userId, tenantId, null, null, null);
        }
    }

    @PostMapping("/preview")
    public Object preview(@RequestHeader("X-API-Key") String apiKey,
                          @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                          @RequestBody PreviewRequest body) {
        TenantId t = new TenantId(body.tenantId() != null && !body.tenantId().isBlank()
                ? body.tenantId()
                : resolveTenant(tenantId));
        // 四要素形态：actor 视为 UserId，评估 resource 是否被允许（agent 名 / 模型名）
        if (body.userId() == null || body.userId().isBlank()) {
            if (body.actor() == null || body.actor().isBlank()) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "GW-1010: userId 或 actor 必填");
            }
            return evaluateFourFactors(t, body);
        }
        UserId u = new UserId(body.userId());
        // 纯函数重放：快照 roles + bindings 后交给 domain service 评估（不命中缓存）
        return roleQueryService.preview(
                roleRepository.findAll(t),
                roleBindingRepository.findByUser(t, u),
                u, t);
    }

    /** 四要素判定：resource 为 agent 名（如 echo-agent）或模型路径（含 models/ 前缀 / 裸模型名）。 */
    private Object evaluateFourFactors(TenantId t, PreviewRequest body) {
        UserId u = new UserId(body.actor());
        PolicyPreview pp = roleQueryService.preview(
                roleRepository.findAll(t),
                roleBindingRepository.findByUser(t, u),
                u, t);
        String resource = body.resource() == null ? "" : body.resource();
        boolean allowed;
        String reason;
        if (resource.contains("models/") || resource.startsWith("model:")) {
            String model = resource.substring(resource.lastIndexOf('/') + 1)
                    .replace("model:", "");
            allowed = pp.allowedModels().stream().anyMatch(m -> m.value().equals(model));
            reason = "model " + model + (allowed ? " 在角色授权模型集内" : " 不在角色授权模型集内");
        } else {
            // 默认按 Agent 名判定（resource 形如 agents/xxx 或裸 agent 名）
            String agent = resource.contains("/")
                    ? resource.substring(resource.lastIndexOf('/') + 1)
                    : resource;
            allowed = pp.allowedAgents().stream().anyMatch(a -> a.equals(agent));
            reason = "agent " + agent + (allowed ? " 在角色授权 Agent 集内" : " 不在角色授权 Agent 集内");
        }
        return java.util.Map.of(
                "allowed", allowed,
                "reason", reason,
                "rule", allowed ? "role-permission" : "default-deny",
                "action", body.action() == null ? "" : body.action());
    }

    private static String resolveTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "primary" : tenantId;
    }
}
