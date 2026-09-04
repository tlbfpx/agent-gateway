package com.company.agentgateway.domain.iam.admin;

/**
 * Admin 账号状态（spec 2026-09-02 §multi-admin §3.2）。
 *
 * <p>状态机：
 * <pre>
 *   ACTIVE ──suspend──► SUSPENDED ──activate──► ACTIVE
 *      │                                           ▲
 *      └─delete──► DELETED  (终态,可审计不可登录)
 * </pre>
 */
public enum AdminStatus {
    ACTIVE,
    SUSPENDED,
    DELETED;

    public boolean canLogin() {
        return this == ACTIVE;
    }

    public static AdminStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        try {
            return AdminStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "unknown status: " + raw + " (use ACTIVE|SUSPENDED|DELETED)");
        }
    }
}
