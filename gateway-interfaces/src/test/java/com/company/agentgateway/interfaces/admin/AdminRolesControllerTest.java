package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.iam.AgentPermission;
import com.company.agentgateway.domain.iam.RbacChangeEvent;
import com.company.agentgateway.domain.iam.RbacChangePublisher;
import com.company.agentgateway.domain.iam.RbacErrorCode;
import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AdminRolesController 单测（spec §GW-RBAC-011：CRUD + 错误码 GW-1010/1012 + 变更发布 + 审计）。
 */
class AdminRolesControllerTest {

    private InMemoryRoles roleRepo;
    private InMemoryAudit auditRepo;
    private RecordingPublisher publisher;
    private AdminRolesController controller;

    @BeforeEach
    void setUp() {
        roleRepo = new InMemoryRoles();
        auditRepo = new InMemoryAudit();
        publisher = new RecordingPublisher();
        controller = new AdminRolesController(roleRepo, auditRepo, publisher);
    }

    /** D2 后控制器接收 RoleRequest DTO（sealed Permission 由 mapPermissions 按字段形态推断）。 */
    private AdminRolesController.RoleRequest role(String id, String name) {
        return new AdminRolesController.RoleRequest(id, name, "d",
                List.of(new AdminRolesController.PermissionDto("hr-agent", List.of(), null, null)));
    }

    @Test
    void crudLifecycle_withPublishAndAudit() {
        // create（无 id → 系统生成）
        var created = controller.create("k", null, role(null, "r1"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        Role saved = created.getBody();
        assertThat(saved.id()).isNotNull();
        assertThat(roleRepo.findById(new TenantId("primary"), saved.id())).contains(saved);
        assertThat(publisher.lastEvent().kind()).isEqualTo(RbacChangeEvent.Kind.ROLE_UPSERT);

        // list
        assertThat(controller.list("k", null)).hasSize(1);

        // update
        Role updated = controller.update("k", null, saved.id().value(), role(saved.id().value(), "r1-v2"));
        assertThat(updated.name()).isEqualTo("r1-v2");

        // delete → 204 + ROLE_DELETE 事件
        assertThat(controller.delete("k", null, saved.id().value()).getStatusCode().value()).isEqualTo(204);
        assertThat(roleRepo.findById(new TenantId("primary"), saved.id())).isEmpty();
        assertThat(publisher.lastEvent().kind()).isEqualTo(RbacChangeEvent.Kind.ROLE_DELETE);

        // 审计含 create/update/delete
        List<AuditRepository.AuditLog> logs = auditRepo.query(new TenantId("primary"), null, null, null, 100);
        assertThat(logs.stream().filter(l -> "role-create".equals(l.action())).count()).isEqualTo(1);
        assertThat(logs.stream().filter(l -> "role-update".equals(l.action())).count()).isEqualTo(1);
        assertThat(logs.stream().filter(l -> "role-delete".equals(l.action())).count()).isEqualTo(1);
    }

    @Test
    void getUnknownRole_returns404_withGW1010() {
        assertThatThrownBy(() -> controller.update("k", null, "r-missing", role("r-missing", "x")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404")
                .hasMessageContaining(RbacErrorCode.ROLE_NOT_FOUND);
        assertThatThrownBy(() -> controller.delete("k", null, "r-missing"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(RbacErrorCode.ROLE_NOT_FOUND);
    }

    @Test
    void emptyPermissions_returns400_withGW1012() {
        AdminRolesController.RoleRequest bad = new AdminRolesController.RoleRequest(null, "r1", "d", List.of());
        assertThatThrownBy(() -> controller.create("k", null, bad))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400")
                .hasMessageContaining(RbacErrorCode.ROLE_PERMISSION_INVALID);
    }

    // ====== 测试桩 ======

    static class InMemoryRoles implements RoleRepository {
        final Map<TenantId, Map<RoleId, Role>> store = new ConcurrentHashMap<>();
        @Override public Optional<Role> findById(TenantId t, RoleId r) {
            return Optional.ofNullable(store.get(t)).map(m -> m.get(r));
        }
        @Override public List<Role> findAll(TenantId t) {
            Map<RoleId, Role> m = store.get(t);
            return m == null ? List.of() : List.copyOf(m.values());
        }
        @Override public void save(TenantId t, Role r) {
            store.computeIfAbsent(t, k -> new ConcurrentHashMap<>()).put(r.id(), r);
        }
        @Override public void delete(TenantId t, RoleId r) {
            Map<RoleId, Role> m = store.get(t);
            if (m != null) m.remove(r);
        }
    }

    static class InMemoryAudit implements AuditRepository {
        final List<AuditLog> logs = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public void append(AuditLog log) { logs.add(log); }
        @Override public List<AuditLog> query(TenantId tenant, AuditEventType type,
                                              Instant from, Instant to, int limit) {
            return logs.stream().filter(l -> l.tenant().equals(tenant)).limit(limit).toList();
        }
    }

    static class RecordingPublisher implements RbacChangePublisher {
        final SubmissionPublisher<RbacChangeEvent> pub = new SubmissionPublisher<>();
        volatile RbacChangeEvent last;
        @Override public Flow.Publisher<RbacChangeEvent> publish(RbacChangeEvent event) {
            last = event;
            pub.submit(event);
            return pub;
        }
        RbacChangeEvent lastEvent() {
            return last;
        }
    }
}
