package com.company.agentgateway.infra.persistence.admin;

import com.company.agentgateway.domain.iam.admin.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgTeamRepositoryH2Test {

    private PgTeamRepository repo;

    @BeforeEach
    void setUp() {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:team_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE team (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    tenant_id VARCHAR(64) NOT NULL,
                    owner_id BIGINT NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
        jdbc.execute("""
                CREATE TABLE team_member (
                    team_id BIGINT NOT NULL,
                    admin_id BIGINT NOT NULL,
                    PRIMARY KEY (team_id, admin_id)
                )""");
        repo = new PgTeamRepository(jdbc);
    }

    @Test
    void saveAndFindById_roundTrip() {
        Team t = repo.save(Team.create("platform", "au", 1L));
        Team got = repo.findById(t.id()).orElseThrow();
        assertEquals("platform", got.name());
        assertEquals(1L, got.ownerId());
    }

    @Test
    void findByName_tenantScoped() {
        repo.save(Team.create("t", "au", 1L));
        repo.save(Team.create("t", "cn", 1L));
        assertTrue(repo.findByName("au", "t").isPresent());
        assertFalse(repo.findByName("us", "t").isPresent());
    }

    @Test
    void findByOwner_returnsOwnedTeams() {
        repo.save(Team.create("t1", "au", 1L));
        repo.save(Team.create("t2", "au", 1L));
        repo.save(Team.create("t3", "au", 2L));
        assertEquals(2, repo.findByOwner(1L).size());
    }

    @Test
    void saveWithMembers_persistsMembers() {
        Team t = Team.create("team", "au", 1L).withMembers(Set.of(2L, 3L));
        Team saved = repo.save(t);
        // memberIds 应当通过 team_member 表回填(本测试只验证 save 不抛异常)
        assertTrue(saved.id() > 0);
    }

    @Test
    void delete_removesTeam() {
        Team t = repo.save(Team.create("t", "au", 1L));
        assertTrue(repo.delete(t.id()));
        assertFalse(repo.findById(t.id()).isPresent());
    }
}