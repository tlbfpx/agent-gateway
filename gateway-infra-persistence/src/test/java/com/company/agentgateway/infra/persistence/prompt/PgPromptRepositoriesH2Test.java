package com.company.agentgateway.infra.persistence.prompt;

import com.company.agentgateway.domain.prompt.PromptTemplate;
import com.company.agentgateway.domain.prompt.PromptTemplateRepository;
import com.company.agentgateway.domain.prompt.PromptVersion;
import com.company.agentgateway.domain.prompt.PromptVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PromptTemplate + PromptVersion H2 内存测。
 */
class PgPromptRepositoriesH2Test {

    private PgPromptTemplateRepository templateRepo;
    private PgPromptVersionRepository versionRepo;

    @BeforeEach
    void setUp() {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:prompt_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE prompt_template (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    description CLOB,
                    owner_id BIGINT NOT NULL,
                    tenant_id VARCHAR(64) NOT NULL,
                    tags CLOB,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
        jdbc.execute("""
                CREATE TABLE prompt_version (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    template_id BIGINT NOT NULL,
                    version INT NOT NULL,
                    system_prompt CLOB,
                    user_prompt CLOB NOT NULL,
                    model VARCHAR(128),
                    params CLOB,
                    author_id BIGINT NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
        templateRepo = new PgPromptTemplateRepository(jdbc);
        versionRepo = new PgPromptVersionRepository(jdbc);
    }

    @Test
    void templateSaveAndFindById_roundTrip() {
        PromptTemplate t = templateRepo.save(PromptTemplate.create(
                "summarize", "x", 1L, "au", List.of("nlp")));
        PromptTemplate got = templateRepo.findById(t.id()).orElseThrow();
        assertEquals("summarize", got.name());
        assertEquals(List.of("nlp"), got.tags());
    }

    @Test
    void template_findByName_tenantScoped() {
        templateRepo.save(PromptTemplate.create("t", "x", 1L, "au", null));
        templateRepo.save(PromptTemplate.create("t", "x", 1L, "cn", null));
        assertTrue(templateRepo.findByName("au", "t").isPresent());
        assertTrue(templateRepo.findByName("cn", "t").isPresent());
        assertFalse(templateRepo.findByName("us", "t").isPresent());
    }

    @Test
    void template_delete_removes() {
        PromptTemplate t = templateRepo.save(PromptTemplate.create("t", "x", 1L, "au", null));
        assertTrue(templateRepo.delete(t.id()));
        assertFalse(templateRepo.findById(t.id()).isPresent());
    }

    @Test
    void template_update_changesFields() {
        PromptTemplate t = templateRepo.save(PromptTemplate.create("t", "x", 1L, "au", null));
        PromptTemplate updated = new PromptTemplate(
                t.id(), "renamed", "new desc", t.ownerId(), t.tenantId(), t.tags(),
                t.createdAt(), java.time.Instant.now());
        templateRepo.save(updated);
        assertEquals("renamed", templateRepo.findById(t.id()).orElseThrow().name());
    }

    @Test
    void version_saveAndFindByVersion() {
        PromptTemplate t = templateRepo.save(PromptTemplate.create("t", "x", 1L, "au", null));
        PromptVersion v = versionRepo.save(PromptVersion.create(
                t.id(), 1, "You are helpful.", "Summarize: {{text}}", "gpt-4o",
                Map.of("temperature", 0.3), 7L));
        assertNotNull(v.id());
        Optional<PromptVersion> got = versionRepo.findByVersion(t.id(), 1);
        assertTrue(got.isPresent());
        assertEquals("gpt-4o", got.get().model());
        assertEquals(0.3, ((Number) got.get().params().get("temperature")).doubleValue());
    }

    @Test
    void version_findByTemplate_sortedDesc() {
        PromptTemplate t = templateRepo.save(PromptTemplate.create("t", "x", 1L, "au", null));
        versionRepo.save(PromptVersion.create(t.id(), 1, null, "v1", null, null, 7L));
        versionRepo.save(PromptVersion.create(t.id(), 3, null, "v3", null, null, 7L));
        versionRepo.save(PromptVersion.create(t.id(), 2, null, "v2", null, null, 7L));
        List<PromptVersion> got = versionRepo.findByTemplate(t.id());
        assertEquals(3, got.size());
        assertEquals(3, got.get(0).version());
        assertEquals(1, got.get(2).version());
    }

    @Test
    void version_deleteByTemplate_removesAllVersions() {
        PromptTemplate t = templateRepo.save(PromptTemplate.create("t", "x", 1L, "au", null));
        versionRepo.save(PromptVersion.create(t.id(), 1, null, "v1", null, null, 7L));
        versionRepo.save(PromptVersion.create(t.id(), 2, null, "v2", null, null, 7L));
        assertTrue(versionRepo.deleteByTemplate(t.id()));
        assertEquals(0, versionRepo.findByTemplate(t.id()).size());
    }
}