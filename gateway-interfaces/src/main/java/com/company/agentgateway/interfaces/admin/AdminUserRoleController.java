package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.iam.RbacChangeEvent;
import com.company.agentgateway.domain.iam.RbacChangePublisher;
import com.company.agentgateway.domain.iam.RbacErrorCode;
import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.iam.RoleBindingRepository;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * 用户角色绑定 REST（spec §19.3 + §GW-RBAC-011）。
 *
 * <p>路径：{@code /v1/admin/users/{userId}/roles}
 * <ul>
 *   <li>GET    /              — 列出用户的角色</li>
 *   <li>POST   /              — 绑定（重复绑定返回 409 + GW-1011；角色不存在 404 + GW-1010）</li>
 *   <li>DELETE /{roleId}      — 解绑（不存在返回 404 + GW-1013）</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin/users/{userId}/roles")
public class AdminUserRoleController {

    private final RoleBindingRepository roleBindingRepository;
    private final RoleRepository roleRepository;
    private final RbacChangePublisher rbacChangePublisher;
    private final AuditRepository auditRepository;

    public AdminUserRoleController(RoleBindingRepository roleBindingRepository,
                                   RoleRepository roleRepository,
                                   RbacChangePublisher rbacChangePublisher,
                                   AuditRepository auditRepository) {
        this.roleBindingRepository = roleBindingRepository;
        this.roleRepository = roleRepository;
        this.rbacChangePublisher = rbacChangePublisher;
        this.auditRepository = auditRepository;
    }

    @GetMapping
    public List<Role> list(@RequestHeader("X-API-Key") String apiKey,
                           @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                           @PathVariable String userId) {
        TenantId t = new TenantId(resolveTenant(tenantId));
        UserId u = new UserId(userId);
        return roleBindingRepository.findByUser(t, u).stream()
                .map(roleId -> roleRepository.findById(t, roleId))
                .flatMap(opt -> opt.stream())
                .toList();
    }

    /** 请求体：{@code {"roleId": "r-1"}} */
    public record BindRequest(String roleId) {}

    @PostMapping
    public ResponseEntity<Void> bind(@RequestHeader("X-API-Key") String apiKey,
                                     @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                     @PathVariable String userId,
                                     @RequestBody BindRequest body) {
        TenantId t = new TenantId(resolveTenant(tenantId));
        UserId u = new UserId(userId);
        if (body == null || body.roleId() == null || body.roleId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roleId is required");
        }
        RoleId roleId = new RoleId(body.roleId());
        if (roleRepository.findById(t, roleId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    RbacErrorCode.ROLE_NOT_FOUND + ": role not found: " + body.roleId());
        }
        if (roleBindingRepository.findByUser(t, u).contains(roleId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    RbacErrorCode.ROLE_BINDING_CONFLICT + ": user " + userId + " already bound to role " + body.roleId());
        }
        roleBindingRepository.bind(t, u, roleId);
        publishAndAudit(t, roleId, u, RbacChangeEvent.Kind.BIND, "role-bind");
        return ResponseEntity.status(201).build();
    }

    @DeleteMapping("/{roleId}")
    public ResponseEntity<Void> unbind(@RequestHeader("X-API-Key") String apiKey,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                       @PathVariable String userId,
                                       @PathVariable String roleId) {
        TenantId t = new TenantId(resolveTenant(tenantId));
        UserId u = new UserId(userId);
        RoleId r = new RoleId(roleId);
        if (!roleBindingRepository.findByUser(t, u).contains(r)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    RbacErrorCode.USER_ROLE_BINDING_NOT_FOUND + ": binding not found: user=" + userId + ", role=" + roleId);
        }
        roleBindingRepository.unbind(t, u, r);
        publishAndAudit(t, r, u, RbacChangeEvent.Kind.UNBIND, "role-unbind");
        return ResponseEntity.noContent().build();
    }

    // ===== helpers =====

    private void publishAndAudit(TenantId t, RoleId roleId, UserId userId,
                                 RbacChangeEvent.Kind kind, String action) {
        try {
            rbacChangePublisher.publish(new RbacChangeEvent(kind, t, roleId, userId, "admin", Instant.now()));
        } catch (Exception e) {
            // swallow（design §2.2）
        }
        auditRepository.append(new AuditRepository.AuditLog(
                "bd-" + System.nanoTime(), t, "admin",
                AuditRepository.AuditLog.ActorType.HUMAN,
                AuditRepository.AuditEventType.GRANT_CREATE,
                Instant.now(),
                "rbac-binding", roleId.value(), action,
                AuditRepository.AuditLog.Result.SUCCESS, null));
    }

    private static String resolveTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "primary" : tenantId;
    }
}
