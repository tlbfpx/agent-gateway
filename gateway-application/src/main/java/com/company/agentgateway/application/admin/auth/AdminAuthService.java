package com.company.agentgateway.application.admin.auth;

import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.iam.admin.AdminStatus;
import com.company.agentgateway.domain.iam.admin.AdminUser;
import com.company.agentgateway.domain.iam.admin.AdminUserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Admin 鉴权服务（spec 2026-09-02 §bcrypt-auth §4）。
 *
 * <p>P0 实现：密码哈希 + 内存 session(token)。
 * R15 加 JWT(本服务可作 issuer)。
 *
 * <p>token 格式：{@code "v1.<adminId>.<randomBase64>"};24h 过期。
 */
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);

    private final AdminUserRepository userRepo;
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private static final long TTL_MS = 24L * 60 * 60 * 1000; // 24h

    public AdminAuthService(AdminUserRepository userRepo) {
        this.userRepo = userRepo;
    }

    /**
     * 用 email + password 登录;返回 session token(写入 {@code X-Admin-Token})。
     *
     * <p>失败抛 {@link SecurityException}(密码错) / {@link IllegalStateException}(账号停用)。
     */
    public LoginResult login(String tenantId, String email, String password) {
        Optional<AdminUser> opt = userRepo.findByEmail(tenantId, email);
        if (opt.isEmpty()) {
            throw new SecurityException("invalid credentials");
        }
        AdminUser user = opt.get();
        if (!user.status().canLogin()) {
            throw new IllegalStateException("account not active: " + email);
        }
        String phc = user.apiKeyHash();
        if (phc == null || phc.isBlank()) {
            throw new SecurityException("account has no password set");
        }
        if (!PasswordHasher.verify(password, phc)) {
            log.warn("admin.login.failed email={} tenant={}", email, tenantId);
            throw new SecurityException("invalid credentials");
        }
        // 生成 session
        String token = "v1." + user.id() + "." + randomToken();
        Session session = new Session(user.id(), user.role(), user.tenantId(),
                System.currentTimeMillis() + TTL_MS);
        sessions.put(token, session);
        // 记录 lastLoginAt
        AdminUser refreshed = new AdminUser(user.id(), user.email(), user.name(),
                user.role(), user.status(), user.tenantId(), user.apiKeyHash(),
                user.createdAt(), Instant.now());
        userRepo.save(refreshed);
        log.info("admin.login.ok email={} role={}", email, user.role());
        return new LoginResult(token, user);
    }

    /** 验证 X-Admin-Token;返回 AdminRole(用于 RBAC) */
    public Optional<AdminRole> verifyToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Session s = sessions.get(token);
        if (s == null) return Optional.empty();
        if (s.expiresAt < System.currentTimeMillis()) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(s.role);
    }

    /** 显式 logout */
    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    /** 设置/重置 Admin 密码(P0 通过 service 直调;R15 接 controller) */
    public AdminUser setPassword(long adminId, String newPassword) {
        AdminUser u = userRepo.findById(adminId)
                .orElseThrow(() -> new IllegalArgumentException("admin not found: " + adminId));
        String phc = PasswordHasher.hash(newPassword);
        AdminUser updated = new AdminUser(u.id(), u.email(), u.name(),
                u.role(), u.status(), u.tenantId(), phc,
                u.createdAt(), u.lastLoginAt());
        return userRepo.save(updated);
    }

    private static String randomToken() {
        byte[] b = new byte[24];
        new java.security.SecureRandom().nextBytes(b);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private record Session(long adminId, AdminRole role, String tenantId, long expiresAt) {}

    public record LoginResult(String token, AdminUser user) {}
}
