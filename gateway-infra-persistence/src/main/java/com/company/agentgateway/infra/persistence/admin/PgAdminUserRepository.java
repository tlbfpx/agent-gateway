package com.company.agentgateway.infra.persistence.admin;

import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.iam.admin.AdminStatus;
import com.company.agentgateway.domain.iam.admin.AdminUser;
import com.company.agentgateway.domain.iam.admin.AdminUserRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AdminUser Pg 实现（spec 2026-09-02 §pg-persistence §4.2）。
 *
 * <p>依赖 {@link JdbcTemplate} + Flyway-managed schema。
 * 自动适配 PostgreSQL / H2(测试)。
 */
public class PgAdminUserRepository implements AdminUserRepository {

    private final JdbcTemplate jdbc;

    public PgAdminUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AdminUser> MAPPER = (rs, n) -> {
        AdminRole role = AdminRole.parse(rs.getString("role"));
        AdminStatus status = AdminStatus.parse(rs.getString("status"));
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        java.sql.Timestamp lastLoginTs = rs.getTimestamp("last_login_at");
        Instant lastLoginAt = lastLoginTs == null ? null : lastLoginTs.toInstant();
        return new AdminUser(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("name"),
                role,
                status,
                rs.getString("tenant_id"),
                rs.getString("api_key_hash"),
                createdAt,
                lastLoginAt);
    };

    @Override
    public AdminUser save(AdminUser user) {
        if (user.id() == 0) {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO admin_user (email, name, role, status, tenant_id, api_key_hash, created_at, last_login_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        new String[] { "id" });
                ps.setString(1, user.email());
                ps.setString(2, user.name());
                ps.setString(3, user.role().name());
                ps.setString(4, user.status().name());
                ps.setString(5, user.tenantId());
                ps.setString(6, user.apiKeyHash());
                ps.setTimestamp(7, Timestamp.from(user.createdAt()));
                if (user.lastLoginAt() != null) ps.setTimestamp(8, Timestamp.from(user.lastLoginAt()));
                else ps.setNull(8, java.sql.Types.TIMESTAMP);
                return ps;
            }, kh);
            return findById(kh.getKey().longValue()).orElseThrow();
        }
        jdbc.update("UPDATE admin_user SET email=?, name=?, role=?, status=?, tenant_id=?, api_key_hash=?, last_login_at=? WHERE id=?",
                user.email(), user.name(), user.role().name(), user.status().name(),
                user.tenantId(), user.apiKeyHash(),
                user.lastLoginAt() == null ? null : Timestamp.from(user.lastLoginAt()),
                user.id());
        return user;
    }

    @Override
    public Optional<AdminUser> findById(long id) {
        return jdbc.query("SELECT * FROM admin_user WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    @Override
    public Optional<AdminUser> findByEmail(String tenantId, String email) {
        return jdbc.query("SELECT * FROM admin_user WHERE tenant_id = ? AND LOWER(email) = LOWER(?)",
                        MAPPER, tenantId, email).stream().findFirst();
    }

    @Override
    public List<AdminUser> findByTenant(String tenantId) {
        return jdbc.query(
                "SELECT * FROM admin_user WHERE tenant_id = ? AND status != 'DELETED' ORDER BY created_at DESC",
                MAPPER, tenantId);
    }

    @Override
    public List<AdminUser> findByRole(String tenantId, AdminRole role) {
        return jdbc.query(
                "SELECT * FROM admin_user WHERE tenant_id = ? AND role = ? AND status != 'DELETED'",
                MAPPER, tenantId, role.name());
    }

    @Override
    public List<AdminUser> query(AdminUserQuery query) {
        StringBuilder sql = new StringBuilder("SELECT * FROM admin_user WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (query.tenantId() != null) { sql.append(" AND tenant_id = ?"); args.add(query.tenantId()); }
        if (query.role() != null) { sql.append(" AND role = ?"); args.add(query.role().name()); }
        if (query.status() != null) { sql.append(" AND status = ?"); args.add(query.status().name()); }
        sql.append(" AND status != 'DELETED'");
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(query.limit());
        args.add(query.offset());
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    @Override
    public boolean delete(long id) {
        // 软删:status -> DELETED
        int rows = jdbc.update("UPDATE admin_user SET status = 'DELETED' WHERE id = ?", id);
        return rows > 0;
    }
}