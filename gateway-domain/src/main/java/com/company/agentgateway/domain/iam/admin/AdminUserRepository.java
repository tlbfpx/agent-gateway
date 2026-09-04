package com.company.agentgateway.domain.iam.admin;

import java.util.List;
import java.util.Optional;

/**
 * AdminUser 持久化端口（spec 2026-09-02 §multi-admin §4.1）。
 *
 * <p>实现：
 * <ul>
 *   <li>P0：{@code InMemoryAdminUserRepository}（R12）</li>
 *   <li>P1：{@code PgAdminUserRepository} + bcrypt（R13）</li>
 * </ul>
 */
public interface AdminUserRepository {

    /** 保存（id==0 → insert；id>0 → update）;返回持久化后的 record。 */
    AdminUser save(AdminUser user);

    /** 按主键查 */
    Optional<AdminUser> findById(long id);

    /** 按 email 查（用于登录） */
    Optional<AdminUser> findByEmail(String tenantId, String email);

    /** 按租户列出（管理端用户管理页） */
    List<AdminUser> findByTenant(String tenantId);

    /** 按角色筛选 */
    List<AdminUser> findByRole(String tenantId, AdminRole role);

    /** 全条件查询 */
    List<AdminUser> query(AdminUserQuery query);

    /** 删除（软删：status=DELETED） */
    boolean delete(long id);

    record AdminUserQuery(
            String tenantId,
            AdminRole role,
            AdminStatus status,
            int limit,
            int offset) {
        public AdminUserQuery {
            limit = limit <= 0 ? 50 : Math.min(limit, 500);
            offset = Math.max(offset, 0);
        }
    }
}
