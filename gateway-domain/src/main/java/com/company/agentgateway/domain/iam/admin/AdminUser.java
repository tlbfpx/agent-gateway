package com.company.agentgateway.domain.iam.admin;

import java.time.Instant;

/**
 * Admin 账号（spec 2026-09-02 §multi-admin §3.3）。
 *
 * <p>一个 Admin 属于一个租户（{@code tenantId}）,有唯一 email,可分配一个角色。
 * 不可变 record；id 由 repo 分配（0 表示未持久化）。
 *
 * <p>{@code apiKeyHash} 是该 Admin 的登录 token 的 bcrypt 哈希;
 * 静态字符串兼容模式：{@code apiKeyHash == null} 表示 owner 走老 X-Admin-Token 链路。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code id} —— 主键;0 未持久化</li>
 *   <li>{@code email} —— 唯一;登录账号</li>
 *   <li>{@code name} —— 显示名</li>
 *   <li>{@code role} —— 见 {@link AdminRole}</li>
 *   <li>{@code status} —— 见 {@link AdminStatus}</li>
 *   <li>{@code tenantId} —— 所属租户</li>
 *   <li>{@code apiKeyHash} —— bcrypt 哈希;null = 静态兼容路径</li>
 *   <li>{@code createdAt} / {@code lastLoginAt} —— 时间戳</li>
 * </ul>
 */
public record AdminUser(
        long id,
        String email,
        String name,
        AdminRole role,
        AdminStatus status,
        String tenantId,
        String apiKeyHash,
        Instant createdAt,
        Instant lastLoginAt) {

    public AdminUser {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        if (!email.contains("@")) {
            throw new IllegalArgumentException("email must be valid: " + email);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** 构造一条待持久化的 Admin（id=0, createdAt=now, status=ACTIVE） */
    public static AdminUser create(
            String email, String name, AdminRole role,
            String tenantId, String apiKeyHash) {
        return new AdminUser(0L, email, name, role, AdminStatus.ACTIVE,
                tenantId, apiKeyHash, Instant.now(), null);
    }

    /** 用于管理后台列表展示的扁平视图 */
    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id);
        m.put("email", email);
        m.put("name", name);
        m.put("role", role.name());
        m.put("status", status.name());
        m.put("tenantId", tenantId);
        m.put("createdAt", createdAt.toString());
        m.put("lastLoginAt", lastLoginAt == null ? "" : lastLoginAt.toString());
        return m;
    }
}
