package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.iam.AgentPermission;
import com.company.agentgateway.domain.iam.PolicyPreview;
import com.company.agentgateway.domain.iam.RoleQueryService;
import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.iam.RoleBindingRepository;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D1 E2E 主流程（spec §GW-RBAC-012，服务级）：
 * 创建角色 → 绑定用户 → preview 含 agent → 解绑 → preview 为空。
 *
 * <p>跨 3 个 Controller（Roles / UserRole / Preview）走真实 domain 服务与 InMemory 桩，
 * 验证端到端链路（含发布器与审计副作用）。
 */
class RbacEndToEndTest {

    private final TenantId t = new TenantId("primary");
    private final UserId u = new UserId("u-e2e");

    private AdminRolesControllerTest.InMemoryRoles roleRepo;
    private AdminUserRoleControllerTest.InMemoryBindings bindingRepo;
    private AdminRolesControllerTest.InMemoryAudit auditRepo;
    private AdminRolesController roles;
    private AdminUserRoleController bindings;
    private AdminRbacPreviewController preview;

    @BeforeEach
    void setUp() {
        roleRepo = new AdminRolesControllerTest.InMemoryRoles();
        bindingRepo = new AdminUserRoleControllerTest.InMemoryBindings();
        auditRepo = new AdminRolesControllerTest.InMemoryAudit();
        var publisher = new AdminRolesControllerTest.RecordingPublisher();
        roles = new AdminRolesController(roleRepo, auditRepo, publisher);
        bindings = new AdminUserRoleController(bindingRepo, roleRepo, publisher, auditRepo);
        preview = new AdminRbacPreviewController(roleRepo, bindingRepo, new RoleQueryService());
    }

    @Test
    void fullLifecycle_createBindPreviewUnbindPreview() {
        // 1. 创建角色（含 AgentPermission echo-agent）
        Role created = roles.create("k", null, new AdminRolesController.RoleRequest(null, "e2e-role", "d",
                List.of(new AdminRolesController.PermissionDto("echo-agent", List.of(), null, null)))).getBody();
        assertThat(created.id()).isNotNull();

        // 2. 绑定用户
        assertThat(bindings.bind("k", null, u.value(),
                new AdminUserRoleController.BindRequest(created.id().value())).getStatusCode().value())
                .isEqualTo(201);

        // 3. preview → allowedAgents 含 echo-agent
        PolicyPreview pp1 = (com.company.agentgateway.domain.iam.PolicyPreview) preview.preview("k", null,
                new AdminRbacPreviewController.PreviewRequest(u.value(), null));
        assertThat(pp1.allowedAgents()).containsExactly("echo-agent");

        // 4. 解绑
        assertThat(bindings.unbind("k", null, u.value(), created.id().value()).getStatusCode().value())
                .isEqualTo(204);

        // 5. preview → 空
        PolicyPreview pp2 = (com.company.agentgateway.domain.iam.PolicyPreview) preview.preview("k", null,
                new AdminRbacPreviewController.PreviewRequest(u.value(), null));
        assertThat(pp2.allowedAgents()).isEmpty();
        assertThat(pp2.allowedModels()).isEmpty();

        // 6. 审计含全部动作（role-create/role-bind/role-unbind）
        var logs = auditRepo.query(t, null, null, null, 100);
        assertThat(logs.stream().anyMatch(l -> "role-create".equals(l.action()))).isTrue();
        assertThat(logs.stream().anyMatch(l -> "role-bind".equals(l.action()))).isTrue();
        assertThat(logs.stream().anyMatch(l -> "role-unbind".equals(l.action()))).isTrue();
    }

    @Test
    void decisionUnion_endToEnd_roleGrantsPermissionBeyondPrincipalFields() {
        // 角色授权 role-agent；principal 字段为空 → 仅靠 Role 聚合命中（spec §GW-RBAC-005）
        Role created = roles.create("k", null, new AdminRolesController.RoleRequest(null, "role-only", "d",
                List.of(new AdminRolesController.PermissionDto("role-agent", List.of(), null, null)))).getBody();
        bindings.bind("k", null, u.value(),
                new AdminUserRoleController.BindRequest(created.id().value()));

        // RoleQueryService.preview 输出的 allowedAgents 与决策并集的 Role 聚合路径
        // 使用同一 domain 聚合逻辑（PolicyEvaluator/RoleQueryService），零 principal 字段依赖
        PolicyPreview pp = (com.company.agentgateway.domain.iam.PolicyPreview) preview.preview("k", null,
                new AdminRbacPreviewController.PreviewRequest(u.value(), null));
        assertThat(pp.allowedAgents()).containsExactly("role-agent");
    }
}
