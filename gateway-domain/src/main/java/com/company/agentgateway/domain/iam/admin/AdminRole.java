package com.company.agentgateway.domain.iam.admin;

/**
 * Admin 角色等级（spec 2026-09-02 §multi-admin §3.1）。
 *
 * <p>权限层级（严格单调）：
 * <ul>
 *   <li>{@link #OWNER} —— 超管;唯一可转让/删除团队、解散 Admin</li>
 *   <li>{@link #ADMIN} —— 管理员;CRUD 业务对象 + 邀请 OPERATOR</li>
 *   <li>{@link #OPERATOR} —— 运营;配置/查询/导出;不能改 Admin 角色</li>
 *   <li>{@link #VIEWER} —— 只读;查审计/成本/Trace</li>
 * </ul>
 */
public enum AdminRole {
    OWNER(4),
    ADMIN(3),
    OPERATOR(2),
    VIEWER(1);

    private final int level;

    AdminRole(int level) {
        this.level = level;
    }

    /** 是否 ≥ 另一个角色（RBAC 判定用） */
    public boolean atLeast(AdminRole other) {
        return this.level >= other.level;
    }

    /** 字符串解析（API 友好,大小写不敏感） */
    public static AdminRole parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        try {
            return AdminRole.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "unknown role: " + raw + " (use OWNER|ADMIN|OPERATOR|VIEWER)");
        }
    }
}
