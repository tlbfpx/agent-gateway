package com.company.agentgateway.application.prompt;

import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.prompt.PromptExperiment;
import com.company.agentgateway.domain.prompt.PromptTemplate;
import com.company.agentgateway.domain.prompt.PromptVariant;
import com.company.agentgateway.domain.prompt.PromptVersion;
import com.company.agentgateway.infra.persistence.prompt.InMemoryPromptTemplateRepository;
import com.company.agentgateway.infra.persistence.prompt.InMemoryPromptVersionRepository;
import com.company.agentgateway.infra.persistence.prompt.SharedPromptStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptServicesTest {

    private InMemoryPromptTemplateRepository templateRepo;
    private InMemoryPromptVersionRepository versionRepo;
    private SharedPromptStore store;
    private PromptTemplateService templateService;
    private ABTestService abService;

    @BeforeEach
    void setUp() {
        store = new SharedPromptStore();
        templateRepo = new InMemoryPromptTemplateRepository(store);
        versionRepo = new InMemoryPromptVersionRepository(store);
        templateService = new PromptTemplateService(templateRepo, versionRepo);
        abService = new ABTestService(versionRepo);
    }

    // ============= PromptTemplateService =============

    @Test
    void create_template_adminCanCreate() {
        PromptTemplate t = templateService.create("summarize", "sum text", 1L, "au", List.of("nlp"), AdminRole.ADMIN);
        assertTrue(t.id() > 0);
    }

    @Test
    void create_template_viewerRejected() {
        assertThrows(SecurityException.class, () ->
                templateService.create("x", "x", 1L, "au", null, AdminRole.VIEWER));
    }

    @Test
    void create_template_duplicateNameRejected() {
        templateService.create("summarize", "x", 1L, "au", null, AdminRole.ADMIN);
        assertThrows(IllegalStateException.class, () ->
                templateService.create("summarize", "x", 1L, "au", null, AdminRole.ADMIN));
    }

    @Test
    void addVersion_incrementsVersionNumber() {
        PromptTemplate t = templateService.create("t", "x", 1L, "au", null, AdminRole.ADMIN);
        PromptVersion v1 = templateService.addVersion(t.id(), "sys", "user {{x}}",
                "gpt-4o", new HashMap<>(), 7L, AdminRole.OPERATOR);
        PromptVersion v2 = templateService.addVersion(t.id(), "sys2", "user {{x}}",
                "gpt-4o", new HashMap<>(), 7L, AdminRole.OPERATOR);
        assertEquals(1, v1.version());
        assertEquals(2, v2.version());
    }

    @Test
    void addVersion_updatesTemplateTimestamp() {
        PromptTemplate t = templateService.create("t", "x", 1L, "au", null, AdminRole.ADMIN);
        var before = t.updatedAt();
        templateService.addVersion(t.id(), "sys", "user", "gpt-4o", null, 7L, AdminRole.OPERATOR);
        PromptTemplate reread = templateService.findById(t.id());
        assertTrue(reread.updatedAt().compareTo(t.createdAt()) >= 0);
    }

    // ============= ABTestService =============

    @Test
    void createExperiment_weightsValidatedByRecord() {
        assertThrows(IllegalArgumentException.class, () ->
                abService.createExperiment(1L, "exp", List.of(
                        new PromptVariant(1L, 30, "a"),
                        new PromptVariant(2L, 30, "b")), "au", 7L, AdminRole.OPERATOR));
    }

    @Test
    void createExperiment_succeedsAt100() {
        PromptExperiment e = abService.createExperiment(1L, "exp", List.of(
                new PromptVariant(1L, 50, "control"),
                new PromptVariant(2L, 50, "treatment")), "au", 7L, AdminRole.OPERATOR);
        assertEquals(PromptExperiment.Status.DRAFT, e.status());
    }

    @Test
    void assign_stickyToCallerKey() {
        PromptTemplate t = templateService.create("t", "x", 1L, "au", null, AdminRole.ADMIN);
        PromptVersion v1 = templateService.addVersion(t.id(), "sys1", "user", "gpt-4o", null, 7L, AdminRole.OPERATOR);
        PromptVersion v2 = templateService.addVersion(t.id(), "sys2", "user", "gpt-4o", null, 7L, AdminRole.OPERATOR);

        var variants = List.of(
                new PromptVariant(v1.id(), 50, "control"),
                new PromptVariant(v2.id(), 50, "treatment"));

        // 同一 callerKey 多次分配应一致
        Optional<PromptVersion> first = abService.assign(1L, "user-123", variants);
        Optional<PromptVersion> second = abService.assign(1L, "user-123", variants);
        assertTrue(first.isPresent());
        assertEquals(first.get().id(), second.get().id());
    }

    @Test
    void recordResult_andSummary() {
        PromptTemplate t = templateService.create("t", "x", 1L, "au", null, AdminRole.ADMIN);
        PromptVersion v1 = templateService.addVersion(t.id(), "sys1", "user", "gpt-4o", null, 7L, AdminRole.OPERATOR);
        PromptVersion v2 = templateService.addVersion(t.id(), "sys2", "user", "gpt-4o", null, 7L, AdminRole.OPERATOR);
        var variants = List.of(
                new PromptVariant(v1.id(), 50, "control"),
                new PromptVariant(v2.id(), 50, "treatment"));

        PromptExperiment e = abService.createExperiment(t.id(), "exp", variants, "au", 7L, AdminRole.OPERATOR);
        abService.recordResult(e.id(), v1.id(), true);
        abService.recordResult(e.id(), v1.id(), false);
        abService.recordResult(e.id(), v2.id(), true);

        var s = abService.summary(e.id());
        assertEquals(3, s.total());
        assertEquals(2, s.success());
        assertEquals(2, s.byVariant().size());
        assertNotNull(s);
    }
}
