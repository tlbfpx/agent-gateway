package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.iam.AgentPermission;
import com.company.agentgateway.domain.iam.RbacErrorCode;
import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AdminUserRoleController 单测（spec §GW-RBAC-011：bind/unbind + GW-1010/1011/1013）。
 */
class AdminUserRoleControllerTest {

    private final TenantId t = new TenantId("primary");
    private final UserId u = new UserId("u-1");

    private AdminRolesControllerTest.InMemoryRoles roleRepo;
    private AdminRolesControllerTest.InMemoryAudit auditRepo;
    private AdminRolesControllerTest.RecordingPublisher publisher;
    private InMemoryBindings bindingRepo;
    private AdminUserRoleController controller;

    @BeforeEach
    void setUp() {
        roleRepo = new AdminRolesControllerTest.InMemoryRoles();
        auditRepo = new AdminRolesControllerTest.InMemoryAudit();
        publisher = new AdminRolesControllerTest.RecordingPublisher();
        bindingRepo = new InMemoryBindings();
        controller = new AdminUserRoleController(bindingRepo, roleRepo, publisher, auditRepo);
        roleRepo.save(t, new Role(new RoleId("r1"), "n", "d",
                Set.of(new AgentPermission("a", Set.of()))));
    }

    @Test
    void bindThenListThenUnbind_lifecycle() {
        // bind → 201 + BIND 事件
        assertThat(controller.bind("k", null, u.value(), new AdminUserRoleController.BindRequest("r1"))
                .getStatusCode().value()).isEqualTo(201);
        assertThat(publisher.lastEvent().kind()).isEqualTo(com.company.agentgateway.domain.iam.RbacChangeEvent.Kind.BIND);

        // list → 返回绑定的角色
        List<Role> roles = controller.list("k", null, u.value());
        assertThat(roles).hasSize(1);
        assertThat(roles.get(0).id()).isEqualTo(new RoleId("r1"));

        // unbind → 204 + UNBIND 事件
        assertThat(controller.unbind("k", null, u.value(), "r1").getStatusCode().value()).isEqualTo(204);
        assertThat(publisher.lastEvent().kind()).isEqualTo(com.company.agentgateway.domain.iam.RbacChangeEvent.Kind.UNBIND);
        assertThat(controller.list("k", null, u.value())).isEmpty();
    }

    @Test
    void duplicateBind_returns409_withGW1011() {
        controller.bind("k", null, u.value(), new AdminUserRoleController.BindRequest("r1"));
        assertThatThrownBy(() -> controller.bind("k", null, u.value(),
                new AdminUserRoleController.BindRequest("r1")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409")
                .hasMessageContaining(RbacErrorCode.ROLE_BINDING_CONFLICT);
    }

    @Test
    void bindUnknownRole_returns404_withGW1010() {
        assertThatThrownBy(() -> controller.bind("k", null, u.value(),
                new AdminUserRoleController.BindRequest("r-404")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(RbacErrorCode.ROLE_NOT_FOUND);
    }

    @Test
    void unbindNotBound_returns404_withGW1013() {
        assertThatThrownBy(() -> controller.unbind("k", null, u.value(), "r1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404")
                .hasMessageContaining(RbacErrorCode.USER_ROLE_BINDING_NOT_FOUND);
    }

    /** InMemory 绑定桩（复用 domain contract test 同构实现）。 */
    static class InMemoryBindings implements com.company.agentgateway.domain.iam.RoleBindingRepository {
        private final java.util.Map<TenantId, java.util.Map<UserId, java.util.Set<RoleId>>> store =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public List<RoleId> findByUser(TenantId tenant, UserId user) {
            var m = store.get(tenant);
            if (m == null) return List.of();
            var s = m.get(user);
            return s == null ? List.of() : List.copyOf(s);
        }

        @Override
        public void bind(TenantId tenant, UserId user, RoleId roleId) {
            store.computeIfAbsent(tenant, k -> new java.util.concurrent.ConcurrentHashMap<>())
                 .computeIfAbsent(user, k -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                 .add(roleId);
        }

        @Override
        public void unbind(TenantId tenant, UserId user, RoleId roleId) {
            var m = store.get(tenant);
            if (m != null) {
                var s = m.get(user);
                if (s != null) s.remove(roleId);
            }
        }
    }
}
