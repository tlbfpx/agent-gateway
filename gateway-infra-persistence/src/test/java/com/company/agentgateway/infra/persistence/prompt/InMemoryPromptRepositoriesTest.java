package com.company.agentgateway.infra.persistence.prompt;

import com.company.agentgateway.domain.prompt.PromptTemplate;
import com.company.agentgateway.domain.prompt.PromptTemplateRepository;
import com.company.agentgateway.domain.prompt.PromptVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryPromptRepositoriesTest {

    private InMemoryPromptTemplateRepository templateRepo;
    private InMemoryPromptVersionRepository versionRepo;
    private SharedPromptStore store;

    @BeforeEach
    void setUp() {
        store = new SharedPromptStore();
        templateRepo = new InMemoryPromptTemplateRepository(store);
        versionRepo = new InMemoryPromptVersionRepository(store);
    }

    // ============= Template =============

    @Test
    void template_save_assignsId() {
        PromptTemplate t = templateRepo.save(PromptTemplate.create("summarize", "x", 1L, "au", null));
        assertTrue(t.id() > 0);
        assertEquals("summarize", templateRepo.findById(t.id()).get().name());
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
    void template_query_filtersByOwner() {
        templateRepo.save(PromptTemplate.create("a", "x", 1L, "au", null));
        templateRepo.save(PromptTemplate.create("b", "x", 2L, "au", null));
        List<PromptTemplate> got = templateRepo.query(new PromptTemplateRepository.Query("au", 1L, 50, 0));
        assertEquals(1, got.size());
        assertEquals("a", got.get(0).name());
    }

    @Test
    void template_delete_cascadesVersions() {
        PromptTemplate t = templateRepo.save(PromptTemplate.create("t", "x", 1L, "au", null));
        versionRepo.save(PromptVersion.create(t.id(), 1, "sys", "user", "gpt-4o", null, 7L));
        versionRepo.save(PromptVersion.create(t.id(), 2, "sys2", "user2", "gpt-4o", null, 7L));
        assertTrue(templateRepo.delete(t.id()));
        assertTrue(versionRepo.findByTemplate(t.id()).isEmpty());
    }

    // ============= Version =============

    @Test
    void version_save_assignsIncrementingIds() {
        PromptTemplate t = templateRepo.save(PromptTemplate.create("t", "x", 1L, "au", null));
        PromptVersion v1 = versionRepo.save(PromptVersion.create(t.id(), 1, "sys", "user", "gpt-4o", null, 7L));
        PromptVersion v2 = versionRepo.save(PromptVersion.create(t.id(), 2, "sys", "user", "gpt-4o", null, 7L));
        assertEquals(v1.id() + 1, v2.id());
    }

    @Test
    void version_findByVersion_unique() {
        PromptTemplate t = templateRepo.save(PromptTemplate.create("t", "x", 1L, "au", null));
        versionRepo.save(PromptVersion.create(t.id(), 1, "sys", "user", "gpt-4o", null, 7L));
        assertTrue(versionRepo.findByVersion(t.id(), 1).isPresent());
        assertFalse(versionRepo.findByVersion(t.id(), 99).isPresent());
    }

    @Test
    void version_findByTemplate_sortedDesc() {
        PromptTemplate t = templateRepo.save(PromptTemplate.create("t", "x", 1L, "au", null));
        versionRepo.save(PromptVersion.create(t.id(), 1, "sys", "user", "gpt-4o", null, 7L));
        versionRepo.save(PromptVersion.create(t.id(), 3, "sys", "user", "gpt-4o", null, 7L));
        versionRepo.save(PromptVersion.create(t.id(), 2, "sys", "user", "gpt-4o", null, 7L));
        List<PromptVersion> got = versionRepo.findByTemplate(t.id());
        assertEquals(3, got.size());
        assertEquals(3, got.get(0).version());
        assertEquals(1, got.get(2).version());
    }

    @Test
    void version_nextVersionNumber_increments() {
        PromptTemplate t = templateRepo.save(PromptTemplate.create("t", "x", 1L, "au", null));
        assertEquals(1, store.nextVersionNumber(t.id()));
        versionRepo.save(PromptVersion.create(t.id(), 1, "sys", "user", "gpt-4o", null, 7L));
        versionRepo.save(PromptVersion.create(t.id(), 2, "sys", "user", "gpt-4o", null, 7L));
        assertEquals(3, store.nextVersionNumber(t.id()));
    }
}
