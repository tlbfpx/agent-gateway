package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.iam.AgentPermission;
import com.company.agentgateway.domain.iam.PolicyPreview;
import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.iam.RoleQueryService;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AdminRbacPreviewController 单测（spec §GW-RBAC-011：纯函数 preview 幂等 10 次）。
 */
class AdminRbacPreviewControllerTest {

    private final TenantId t = new TenantId("primary");
    private final UserId u = new UserId("u-1");

    private AdminRolesControllerTest.InMemoryRoles roleRepo;
    private AdminUserRoleControllerTest.InMemoryBindings bindingRepo;
    private AdminRbacPreviewController controller;

    @BeforeEach
    void setUp() {
        roleRepo = new AdminRolesControllerTest.InMemoryRoles();
        bindingRepo = new AdminUserRoleControllerTest.InMemoryBindings();
        controller = new AdminRbacPreviewController(roleRepo, bindingRepo, new RoleQueryService());
        roleRepo.save(t, new Role(new RoleId("r1"), "n", "d",
                Set.of(new AgentPermission("hr-agent", Set.of()))));
        bindingRepo.bind(t, u, new RoleId("r1"));
    }

    @Test
    void preview_returnsAggregatedAgents() {
        PolicyPreview pp = (com.company.agentgateway.domain.iam.PolicyPreview) controller.preview("k", null,
                new AdminRbacPreviewController.PreviewRequest(u.value(), null));
        assertThat(pp.allowedAgents()).containsExactly("hr-agent");
        assertThat(pp.allowedModels()).isEmpty();
    }

    @Test
    void preview_isIdempotent_across10Calls() {
        PolicyPreview first = null;
        for (int i = 0; i < 10; i++) {
            PolicyPreview pp = (com.company.agentgateway.domain.iam.PolicyPreview) controller.preview("k", null,
                    new AdminRbacPreviewController.PreviewRequest(u.value(), null));
            if (first == null) {
                first = pp;
            } else {
                // spec §GW-RBAC-011：连发 10 次 equals 一致
                assertThat(pp).isEqualTo(first);
            }
        }
        assertThat(first.allowedAgents()).containsExactly("hr-agent");
    }

    @Test
    void preview_emptyBindings_returnsEmptyPreview() {
        PolicyPreview pp = (com.company.agentgateway.domain.iam.PolicyPreview) controller.preview("k", null,
                new AdminRbacPreviewController.PreviewRequest("u-nobody", null));
        assertThat(pp.allowedAgents()).isEmpty();
        assertThat(pp.allowedModels()).isEmpty();
    }
}
