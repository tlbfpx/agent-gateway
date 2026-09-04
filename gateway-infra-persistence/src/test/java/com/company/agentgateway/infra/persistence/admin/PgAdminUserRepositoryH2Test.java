package com.company.agentgateway.infra.persistence.admin;

import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.iam.admin.AdminStatus;
import com.company.agentgateway.domain.iam.admin.AdminUser;
import com.company.agentgateway.domain.iam.admin.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgAdminUserRepositoryH2Test {

    private PgAdminUserRepository repo;

    @BeforeEach
    void setUp() {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:admin_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE admin_user (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    email VARCHAR(255) NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    role VARCHAR(16) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    tenant_id VARCHAR(64) NOT NULL,
                    api_key_hash CLOB,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_login_at TIMESTAMP
                )""");
        repo = new PgAdminUserRepository(jdbc);
    }

    @Test
    void saveAndFindById_roundTrip() {
        AdminUser u = repo.save(AdminUser.create("alice@x.com", "Alice",
                AdminRole.ADMIN, "au", "$pbkdf2$..."));
        AdminUser got = repo.findById(u.id()).orElseThrow();
        assertEquals("alice@x.com", got.email());
        assertEquals(AdminRole.ADMIN, got.role());
        assertEquals(AdminStatus.ACTIVE, got.status());
        assertEquals("$pbkdf2$...", got.apiKeyHash());
    }

    @Test
    void findByEmail_caseInsensitiveAndTenantScoped() {
        repo.save(AdminUser.create("alice@x.com", "Alice", AdminRole.ADMIN, "au", null));
        repo.save(AdminUser.create("alice@x.com", "Alice", AdminRole.ADMIN, "cn", null));
        assertNotNull(repo.findByEmail("au", "ALICE@X.COM").orElse(null));
        assertNotNull(repo.findByEmail("cn", "alice@x.com").orElse(null));
        assertTrue(repo.findByEmail("us", "alice@x.com").isEmpty());
    }

    @Test
    void findByTenant_excludesDeleted() {
        AdminUser u = repo.save(AdminUser.create("a@x.com", "A", AdminRole.VIEWER, "au", null));
        repo.delete(u.id());
        assertTrue(repo.findByTenant("au").stream().noneMatch(x -> x.id() == u.id()));
    }

    @Test
    void update_changesRole() {
        AdminUser u = repo.save(AdminUser.create("a@x.com", "A", AdminRole.VIEWER, "au", null));
        AdminUser updated = new AdminUser(u.id(), u.email(), u.name(),
                AdminRole.ADMIN, u.status(), u.tenantId(), u.apiKeyHash(),
                u.createdAt(), u.lastLoginAt());
        repo.save(updated);
        assertEquals(AdminRole.ADMIN, repo.findById(u.id()).orElseThrow().role());
    }

    @Test
    void query_filtersByStatus() {
        repo.save(AdminUser.create("a@x.com", "A", AdminRole.ADMIN, "au", null));
        AdminUser s = repo.save(AdminUser.create("b@x.com", "B", AdminRole.ADMIN, "au", null));
        repo.delete(s.id());

        var q = new AdminUserRepository.AdminUserQuery(
                "au", null, AdminStatus.ACTIVE, 50, 0);
        assertEquals(1, repo.query(q).size());
    }
}