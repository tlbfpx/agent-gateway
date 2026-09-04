package com.company.agentgateway.infra.persistence.rbac;

import com.company.agentgateway.domain.iam.RoleBinding;
import com.company.agentgateway.domain.iam.RoleBindingRepository;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * RoleBindingRepository 的 PG 实现（add-pg-persistence）。
 *
 * <p>替代 InMemoryRoleBindingRepository（保留为降级）；D1 接口签名零变化。
 * bind/unbind 幂等（ON CONFLICT DO NOTHING / DELETE 不存在时不报错）。
 */
public class PgRoleBindingRepository implements RoleBindingRepository {

    private final JdbcTemplate jdbc;

    public PgRoleBindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RoleId> findByUser(TenantId tenant, UserId user) {
        return jdbc.query(
                "SELECT role_id FROM rbac_role_bindings WHERE tenant_id = ? AND user_id = ? ORDER BY role_id",
                (rs, i) -> new RoleId(rs.getString("role_id")),
                tenant.value(), user.value());
    }

    @Override
    public void bind(TenantId tenant, UserId user, RoleId roleId) {
        jdbc.update("""
                INSERT INTO rbac_role_bindings (tenant_id, user_id, role_id)
                VALUES (?, ?, ?)
                ON CONFLICT (tenant_id, user_id, role_id) DO NOTHING
                """, tenant.value(), user.value(), roleId.value());
    }

    @Override
    public void unbind(TenantId tenant, UserId user, RoleId roleId) {
        jdbc.update("DELETE FROM rbac_role_bindings WHERE tenant_id = ? AND user_id = ? AND role_id = ?",
                tenant.value(), user.value(), roleId.value());
    }

    /** 全量快照（管理员视图 / 缓存重建用）。 */
    public List<RoleBinding> findAll(TenantId tenant) {
        return jdbc.query(
                "SELECT user_id, role_id FROM rbac_role_bindings WHERE tenant_id = ? ORDER BY user_id, role_id",
                (rs, i) -> new RoleBinding(tenant, new UserId(rs.getString("user_id")),
                        new RoleId(rs.getString("role_id"))),
                tenant.value());
    }
}
