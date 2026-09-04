package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.iam.AgentPermission;
import com.company.agentgateway.domain.iam.ModelPermission;
import com.company.agentgateway.domain.iam.Permission;
import com.company.agentgateway.domain.iam.RbacChangeEvent;
import com.company.agentgateway.domain.iam.SkillPermission;
import com.company.agentgateway.domain.iam.RbacChangePublisher;
import com.company.agentgateway.domain.iam.RbacErrorCode;
import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * 角色管理 REST（spec §19.3 + §GW-RBAC-011 逐字对齐）。
 *
 * <p>路径：{@code /v1/admin/roles}
 * <ul>
 *   <li>GET    /                  — 列表</li>
 *   <li>POST   /                  — 新增（id 由系统生成）</li>
 *   <li>PUT    /{id}              — 更新（id 路径指定）</li>
 *   <li>DELETE /{id}              — 删除</li>
 * </ul>
 *
 * <p>错误码：GW-1010（角色不存在 404）/ GW-1012（permissions 非法 400）/ GW-4204（兜底）。
 * 变更发布：RbacChangePublisher（ROLE_UPSERT/ROLE_DELETE）；审计：GRANT_CREATE 系列。
 */
@RestController
@RequestMapping("/v1/admin/roles")
public class AdminRolesController {

    private final RoleRepository roleRepository;
    private final AuditRepository auditRepository;
    private final RbacChangePublisher rbacChangePublisher;

    public AdminRolesController(RoleRepository roleRepository,
                                AuditRepository auditRepository,
                                RbacChangePublisher rbacChangePublisher) {
        this.roleRepository = roleRepository;
        this.auditRepository = auditRepository;
        this.rbacChangePublisher = rbacChangePublisher;
    }

    @GetMapping
    public List<Role> list(@RequestHeader("X-API-Key") String apiKey,
                           @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        return roleRepository.findAll(new TenantId(resolveTenant(tenantId)));
    }

    @PostMapping
    public ResponseEntity<Role> create(@RequestHeader("X-API-Key") String apiKey,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                       @RequestBody RoleRequest body) {
        TenantId t = new TenantId(resolveTenant(tenantId));
        Role mapped = mapRole(body, body.id() != null ? body.id() : "r-" + System.nanoTime());
        validatePermissions(mapped);
        RoleId id = mapped.id();
        Role saved = new Role(id, mapped.name(), mapped.description(), mapped.permissions());
        roleRepository.save(t, saved);
        publishAndAudit(t, id, RbacChangeEvent.Kind.ROLE_UPSERT, "role-create");
        return ResponseEntity.status(201).body(saved);
    }

    @PutMapping("/{id}")
    public Role update(@RequestHeader("X-API-Key") String apiKey,
                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                       @PathVariable String id,
                       @RequestBody RoleRequest body) {
        TenantId t = new TenantId(resolveTenant(tenantId));
        RoleId roleId = new RoleId(id);
        if (roleRepository.findById(t, roleId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    RbacErrorCode.ROLE_NOT_FOUND + ": role not found: " + id);
        }
        Role mapped = mapRole(body, id);
        validatePermissions(mapped);
        Role updated = new Role(roleId, mapped.name(), mapped.description(), mapped.permissions());
        roleRepository.save(t, updated);
        publishAndAudit(t, roleId, RbacChangeEvent.Kind.ROLE_UPSERT, "role-update");
        return updated;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@RequestHeader("X-API-Key") String apiKey,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                       @PathVariable String id) {
        TenantId t = new TenantId(resolveTenant(tenantId));
        RoleId roleId = new RoleId(id);
        if (roleRepository.findById(t, roleId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    RbacErrorCode.ROLE_NOT_FOUND + ": role not found: " + id);
        }
        roleRepository.delete(t, roleId);
        publishAndAudit(t, roleId, RbacChangeEvent.Kind.ROLE_DELETE, "role-delete");
        return ResponseEntity.noContent().build();
    }

    // ===== helpers =====

    private static void validatePermissions(Role body) {
        if (body.permissions() == null || body.permissions().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    RbacErrorCode.ROLE_PERMISSION_INVALID + ": permissions must contain at least one entry");
        }
    }

    private void publishAndAudit(TenantId t, RoleId roleId,
                                 RbacChangeEvent.Kind kind, String action) {
        try {
            rbacChangePublisher.publish(new RbacChangeEvent(kind, t, roleId, null, "admin", Instant.now()));
        } catch (Exception e) {
            // swallow（design §2.2 失败语义）
        }
        auditRepository.append(new AuditRepository.AuditLog(
                "pl-" + System.nanoTime(), t, "admin",
                AuditRepository.AuditLog.ActorType.HUMAN,
                AuditRepository.AuditEventType.GRANT_CREATE,
                Instant.now(),
                "rbac-role", roleId.value(), action,
                AuditRepository.AuditLog.Result.SUCCESS, null));
    }

    /** UI/REST 请求体：permissions 按字段形态推断 sealed 子类型（与 UI RbacPermission 形态对齐）。 */
    public record RoleRequest(String id, String name, String description, List<PermissionDto> permissions) {}

    /** 扁平权限形态：{agentName,allowedSkills} / {models} / {agentName,skillName}。 */
    public record PermissionDto(String agentName, java.util.List<String> allowedSkills,
                                java.util.List<String> models, String skillName) {}

    /** DTO → sealed Permission 映射（字段形态推断：models → Model；skillName → Skill；否则 Agent）。 */
    private static java.util.Set<Permission> mapPermissions(List<PermissionDto> dtos) {
        if (dtos == null) return java.util.Set.of();
        java.util.Set<Permission> perms = new java.util.LinkedHashSet<>();
        for (PermissionDto d : dtos) {
            if (d == null) continue;
            if (d.models() != null && !d.models().isEmpty()) {
                perms.add(new ModelPermission(
                        d.models().stream().map(ModelId::new).collect(java.util.stream.Collectors.toSet())));
            } else if (d.skillName() != null && !d.skillName().isBlank()) {
                perms.add(new SkillPermission(d.agentName(), d.skillName()));
            } else if (d.agentName() != null && !d.agentName().isBlank()) {
                perms.add(new AgentPermission(d.agentName(),
                        d.allowedSkills() == null ? java.util.Set.of() : java.util.Set.copyOf(d.allowedSkills())));
            }
        }
        return perms;
    }

    private static Role mapRole(RoleRequest body, String id) {
        return new Role(new RoleId(id), body.name(), body.description(), mapPermissions(body.permissions()));
    }

    private static String resolveTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "primary" : tenantId;
    }
}
