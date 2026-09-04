package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.audit.AuditRepository.AuditEventType;
import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * RBAC 策略中心（兼容端点 · spec §GW-RBAC-007）。
 *
 * <p><b>已迁移</b>：CRUD 操作请用 {@code /v1/admin/roles}（AdminRolesController，Chunk 4 落地）。
 * 本 controller 保留 {@code /v1/admin/rbac/policies} 路径作为 deprecation 入口，
 * 响应头 {@code Deprecation: true}。
 *
 * <p>字段从 Map&lt;String,Object&gt; 切到 RoleRepository（spec §GW-RBAC-007 决议）。
 */
@RestController
@RequestMapping("/v1/admin/rbac")
public class AdminPolicyController {

    private final AuditRepository auditRepository;
    private final RoleRepository roleRepository;

    public AdminPolicyController(AuditRepository auditRepository, RoleRepository roleRepository) {
        this.auditRepository = auditRepository;
        this.roleRepository = roleRepository;
    }

    /** 列表：读 RoleRepository；保留 Deprecation 头。 */
    @GetMapping("/policies")
    public ResponseEntity<List<Role>> list(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        TenantId t = new TenantId(resolveTenant(tenantId));
        List<Role> roles = roleRepository.findAll(t).stream()
                .sorted((a, b) -> Integer.compare(b.permissions().size(), a.permissions().size()))
                .toList();
        return ResponseEntity.ok().header("Deprecation", "true").body(roles);
    }

    /** 创建（deprecation）：委托 RoleRepository。 */
    @PostMapping("/policies")
    public ResponseEntity<Role> create(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestBody Role role) {
        TenantId t = new TenantId(resolveTenant(tenantId));
        Role toSave = new Role(
                role.id() != null ? role.id() : new RoleId("r-" + System.currentTimeMillis()),
                role.name(), role.description(), role.permissions());
        roleRepository.save(t, toSave);
        appendAudit(tenantId, "policy-create", toSave.id().value());
        return ResponseEntity.status(201).header("Deprecation", "true").body(toSave);
    }

    @PutMapping("/policies/{id}")
    public ResponseEntity<Role> update(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @PathVariable String id,
            @RequestBody Role body) {
        TenantId t = new TenantId(resolveTenant(tenantId));
        if (roleRepository.findById(t, new RoleId(id)).isEmpty()) {
            return ResponseEntity.notFound().header("Deprecation", "true").build();
        }
        Role updated = new Role(new RoleId(id), body.name(), body.description(), body.permissions());
        roleRepository.save(t, updated);
        appendAudit(tenantId, "policy-update", id);
        return ResponseEntity.ok().header("Deprecation", "true").body(updated);
    }

    @DeleteMapping("/policies/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @PathVariable String id) {
        TenantId t = new TenantId(resolveTenant(tenantId));
        if (roleRepository.findById(t, new RoleId(id)).isEmpty()) {
            return ResponseEntity.notFound().header("Deprecation", "true").build();
        }
        roleRepository.delete(t, new RoleId(id));
        appendAudit(tenantId, "policy-delete", id);
        return ResponseEntity.noContent().header("Deprecation", "true").build();
    }

    private void appendAudit(String tenantId, String action, String resource) {
        auditRepository.append(new AuditRepository.AuditLog(
                "pl-" + System.nanoTime(),
                new TenantId(resolveTenant(tenantId)),
                "admin",
                AuditRepository.AuditLog.ActorType.HUMAN,
                AuditEventType.GRANT_CREATE,
                Instant.now(),
                "rbac-policy",
                resource,
                action,
                AuditRepository.AuditLog.Result.SUCCESS,
                null));
    }

    private static String resolveTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "primary" : tenantId;
    }
}
