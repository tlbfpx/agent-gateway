package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.iam.AgentPermission;
import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B.8 类型化改造后的 AdminPolicyController 测试（spec §GW-RBAC-007）。
 *
 * <p>旧 Map 契约测试随 Map 版 controller 一并废弃（前端迁移至 /v1/admin/roles，Chunk 4）；
 * 本文件覆盖：Deprecation 头 + 类型化 CRUD + 审计追加。
 */
class AdminPolicyControllerTest {

    private static final TenantId T = new TenantId("primary");

    private final RoleRepository roleRepo = new InMemoryRoleStub();
    private final InMemoryAudit auditRepo = new InMemoryAudit();
    private AdminPolicyController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminPolicyController(auditRepo, roleRepo);
    }

    private Role role(String id, String name) {
        return new Role(id == null ? null : new RoleId(id), name, "desc",
                Set.of(new AgentPermission("hr-agent", Set.of())));
    }

    @Test
    void typedCrudLifecycle() {
        // create
        var createdResp = controller.create("k", null, role(null, "dev-role"));
        assertThat(createdResp.getStatusCode().value()).isEqualTo(201);
        assertThat(createdResp.getHeaders().getFirst("Deprecation")).isEqualTo("true");
        Role created = createdResp.getBody();
        assertThat(created).isNotNull();
        assertThat(created.id()).isNotNull();
        assertThat(roleRepo.findById(T, created.id())).contains(created);

        // list（读 RoleRepository，含 Deprecation 头）
        var listResp = controller.list("k", null);
        assertThat(listResp.getStatusCode().value()).isEqualTo(200);
        assertThat(listResp.getHeaders().getFirst("Deprecation")).isEqualTo("true");
        assertThat(listResp.getBody()).hasSize(1);

        // update
        var updatedResp = controller.update("k", null, created.id().value(),
                role(created.id().value(), "dev-role-v2"));
        assertThat(updatedResp.getStatusCode().value()).isEqualTo(200);
        assertThat(updatedResp.getBody().name()).isEqualTo("dev-role-v2");

        // delete
        assertThat(controller.delete("k", null, created.id().value()).getStatusCode().value()).isEqualTo(204);
        assertThat(roleRepo.findById(T, created.id())).isEmpty();
    }

    @Test
    void unknownId_returns404() {
        assertThat(controller.update("k", null, "r-404", role("r-404", "x")).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.delete("k", null, "r-404").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void mutationsAppendAudit() {
        Role created = controller.create("k", null, role(null, "r1")).getBody();
        controller.delete("k", null, created.id().value());
        List<AuditRepository.AuditLog> logs = auditRepo.query(T, null, null, null, 100);
        assertThat(logs.stream().anyMatch(a -> "policy-create".equals(a.action()))).isTrue();
        assertThat(logs.stream().anyMatch(a -> "policy-delete".equals(a.action()))).isTrue();
    }

    /** 测试内联 InMemory RoleRepository 桩（模块不依赖 infra-security）。 */
    static class InMemoryRoleStub implements RoleRepository {
        final Map<TenantId, Map<RoleId, Role>> store = new ConcurrentHashMap<>();

        @Override
        public Optional<Role> findById(TenantId tenant, RoleId roleId) {
            return Optional.ofNullable(store.get(tenant)).map(m -> m.get(roleId));
        }

        @Override
        public List<Role> findAll(TenantId tenant) {
            Map<RoleId, Role> m = store.get(tenant);
            return m == null ? List.of() : List.copyOf(m.values());
        }

        @Override
        public void save(TenantId tenant, Role role) {
            store.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>()).put(role.id(), role);
        }

        @Override
        public void delete(TenantId tenant, RoleId roleId) {
            Map<RoleId, Role> m = store.get(tenant);
            if (m != null) m.remove(roleId);
        }
    }

    /** 最小 InMemory 审计桩（query 签名对齐 AuditRepository：tenant/type/from/to/limit）。 */
    static class InMemoryAudit implements AuditRepository {
        private final java.util.List<AuditLog> logs = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void append(AuditLog log) {
            logs.add(log);
        }

        @Override
        public List<AuditLog> query(TenantId tenant, AuditEventType type,
                                    Instant from, Instant to, int limit) {
            return logs.stream()
                    .filter(l -> l.tenant().equals(tenant))
                    .filter(l -> type == null || l.eventType() == type)
                    .limit(limit)
                    .toList();
        }
    }
}
