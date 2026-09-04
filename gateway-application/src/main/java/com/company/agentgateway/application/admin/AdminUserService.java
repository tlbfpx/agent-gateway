package com.company.agentgateway.application.admin;

import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.iam.admin.AdminStatus;
import com.company.agentgateway.domain.iam.admin.AdminUser;
import com.company.agentgateway.domain.iam.admin.AdminUserRepository;
import com.company.agentgateway.domain.iam.admin.AdminUserRepository.AdminUserQuery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * AdminUser 用例层（spec 2026-09-02 §multi-admin §4.2）。
 *
 * <p>RBAC 闸门：本 service 接收 caller 角色判定是否允许目标操作；
 * UI / controller 在调用前先识别 caller role。
 */
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private final AdminUserRepository repository;

    public AdminUserService(AdminUserRepository repository) {
        this.repository = repository;
    }

    /** 注册一个新 Admin(默认 ACTIVE;R13 接 bcrypt)。 */
    public AdminUser register(String email, String name, AdminRole role,
                               String tenantId, String apiKeyHash, AdminRole callerRole) {
        requireCaller(callerRole, AdminRole.ADMIN);
        if (role == AdminRole.OWNER && callerRole != AdminRole.OWNER) {
            throw new SecurityException("only OWNER can create OWNER admin");
        }
        Optional<AdminUser> existing = repository.findByEmail(tenantId, email);
        if (existing.isPresent() && existing.get().status() != AdminStatus.DELETED) {
            throw new IllegalStateException("email already exists: " + email);
        }
        AdminUser u = AdminUser.create(email, name, role, tenantId, apiKeyHash);
        AdminUser saved = repository.save(u);
        log.info("admin.registered id={} email={} role={} by={}",
                saved.id(), saved.email(), saved.role(), callerRole);
        return saved;
    }

    /** 修改角色;OWNER 角色不可被非 OWNER 修改。 */
    public AdminUser changeRole(long id, AdminRole newRole, AdminRole callerRole) {
        requireCaller(callerRole, AdminRole.ADMIN);
        AdminUser u = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("admin not found: " + id));
        if (u.role() == AdminRole.OWNER && callerRole != AdminRole.OWNER) {
            throw new SecurityException("only OWNER can change OWNER role");
        }
        if (newRole == AdminRole.OWNER && callerRole != AdminRole.OWNER) {
            throw new SecurityException("only OWNER can promote to OWNER");
        }
        AdminUser updated = new AdminUser(u.id(), u.email(), u.name(),
                newRole, u.status(), u.tenantId(), u.apiKeyHash(),
                u.createdAt(), u.lastLoginAt());
        AdminUser saved = repository.save(updated);
        log.info("admin.roleChanged id={} {} → {} by={}", id, u.role(), newRole, callerRole);
        return saved;
    }

    /** 暂停账号。 */
    public AdminUser suspend(long id, AdminRole callerRole) {
        requireCaller(callerRole, AdminRole.ADMIN);
        AdminUser u = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("admin not found: " + id));
        if (u.role() == AdminRole.OWNER && callerRole != AdminRole.OWNER) {
            throw new SecurityException("only OWNER can suspend OWNER");
        }
        AdminUser updated = new AdminUser(u.id(), u.email(), u.name(),
                u.role(), AdminStatus.SUSPENDED, u.tenantId(), u.apiKeyHash(),
                u.createdAt(), u.lastLoginAt());
        return repository.save(updated);
    }

    /** 重新激活。 */
    public AdminUser activate(long id, AdminRole callerRole) {
        requireCaller(callerRole, AdminRole.ADMIN);
        AdminUser u = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("admin not found: " + id));
        AdminUser updated = new AdminUser(u.id(), u.email(), u.name(),
                u.role(), AdminStatus.ACTIVE, u.tenantId(), u.apiKeyHash(),
                u.createdAt(), u.lastLoginAt());
        return repository.save(updated);
    }

    /** 软删(状态置 DELETED)。 */
    public boolean delete(long id, AdminRole callerRole) {
        requireCaller(callerRole, AdminRole.OWNER);
        AdminUser u = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("admin not found: " + id));
        if (u.role() == AdminRole.OWNER) {
            throw new SecurityException("cannot delete OWNER; suspend instead");
        }
        boolean ok = repository.delete(id);
        log.info("admin.deleted id={} by={}", id, callerRole);
        return ok;
    }

    /** 记录登录时间(供登录端点调用;R13 接入真实 auth 后由 auth 模块触发)。 */
    public AdminUser recordLogin(long id) {
        AdminUser u = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("admin not found: " + id));
        AdminUser updated = new AdminUser(u.id(), u.email(), u.name(),
                u.role(), u.status(), u.tenantId(), u.apiKeyHash(),
                u.createdAt(), Instant.now());
        return repository.save(updated);
    }

    public Optional<AdminUser> findById(long id) { return repository.findById(id); }

    public List<AdminUser> findByTenant(String tenantId) { return repository.findByTenant(tenantId); }

    public List<AdminUser> query(AdminUserQuery q) { return repository.query(q); }

    /** 闸门:caller 角色必须 ≥ required。 */
    private static void requireCaller(AdminRole caller, AdminRole required) {
        if (caller == null || !caller.atLeast(required)) {
            throw new SecurityException("caller role " + caller + " insufficient (need " + required + ")");
        }
    }
}
