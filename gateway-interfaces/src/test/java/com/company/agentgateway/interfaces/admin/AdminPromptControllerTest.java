package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.application.prompt.ABTestService;
import com.company.agentgateway.application.prompt.PromptTemplateService;
import com.company.agentgateway.infra.persistence.prompt.InMemoryPromptTemplateRepository;
import com.company.agentgateway.infra.persistence.prompt.InMemoryPromptVersionRepository;
import com.company.agentgateway.infra.persistence.prompt.SharedPromptStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminPromptControllerTest {

    private AdminPromptController controller;
    private SharedPromptStore store;

    @BeforeEach
    void setUp() {
        store = new SharedPromptStore();
        PromptTemplateService templateService = new PromptTemplateService(
                new InMemoryPromptTemplateRepository(store),
                new InMemoryPromptVersionRepository(store));
        ABTestService abService = new ABTestService(new InMemoryPromptVersionRepository(store));
        controller = new AdminPromptController(templateService, abService);
    }

    @Test
    void createTemplate_returns201() {
        Map<String, Object> body = templateBody("summarize", 1L, "au");
        var resp = controller.createTemplate("token", body);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertEquals("summarize", resp.getBody().get("name"));
    }

    @Test
    void addVersion_autoIncrements() {
        var t = controller.createTemplate("token", templateBody("t", 1L, "au"));
        long templateId = ((Number) t.getBody().get("id")).longValue();
        var v1 = controller.addVersion("token", templateId, versionBody("user {{x}}", 7L));
        var v2 = controller.addVersion("token", templateId, versionBody("user {{x}}", 7L));
        assertEquals(1, v1.getBody().get("version"));
        assertEquals(2, v2.getBody().get("version"));
    }

    @Test
    void getTemplate_includesVersions() {
        var t = controller.createTemplate("token", templateBody("t", 1L, "au"));
        long templateId = ((Number) t.getBody().get("id")).longValue();
        controller.addVersion("token", templateId, versionBody("user 1", 7L));
        controller.addVersion("token", templateId, versionBody("user 2", 7L));

        Map<String, Object> got = controller.getTemplate("token", templateId);
        List<?> versions = (List<?>) got.get("versions");
        assertEquals(2, versions.size());
    }

    @Test
    void createExperiment_validatesWeights() {
        var t = controller.createTemplate("token", templateBody("t", 1L, "au"));
        long templateId = ((Number) t.getBody().get("id")).longValue();
        var v1 = controller.addVersion("token", templateId, versionBody("user1", 7L));
        long v1Id = ((Number) v1.getBody().get("id")).longValue();

        // 30 + 30 = 60 != 100
        Map<String, Object> expBody = new LinkedHashMap<>();
        expBody.put("name", "exp1");
        expBody.put("tenantId", "au");
        expBody.put("createdBy", 7L);
        List<Map<String, Object>> variants = new ArrayList<>();
        Map<String, Object> va = new LinkedHashMap<>();
        va.put("versionId", v1Id);
        va.put("weight", 30);
        va.put("label", "a");
        variants.add(va);
        Map<String, Object> vb = new LinkedHashMap<>();
        vb.put("versionId", v1Id);
        vb.put("weight", 30);
        vb.put("label", "b");
        variants.add(vb);
        expBody.put("variants", variants);

        assertThrows(ResponseStatusException.class,
                () -> controller.createExperiment("token", templateId, expBody));
    }

    @Test
    void createExperiment_succeedsAt100() {
        var t = controller.createTemplate("token", templateBody("t", 1L, "au"));
        long templateId = ((Number) t.getBody().get("id")).longValue();
        var v1 = controller.addVersion("token", templateId, versionBody("user1", 7L));
        long v1Id = ((Number) v1.getBody().get("id")).longValue();
        var v2 = controller.addVersion("token", templateId, versionBody("user2", 7L));
        long v2Id = ((Number) v2.getBody().get("id")).longValue();

        Map<String, Object> expBody = new LinkedHashMap<>();
        expBody.put("name", "exp1");
        expBody.put("tenantId", "au");
        expBody.put("createdBy", 7L);
        List<Map<String, Object>> variants = new ArrayList<>();
        Map<String, Object> va = new LinkedHashMap<>();
        va.put("versionId", v1Id);
        va.put("weight", 50);
        va.put("label", "control");
        variants.add(va);
        Map<String, Object> vb = new LinkedHashMap<>();
        vb.put("versionId", v2Id);
        vb.put("weight", 50);
        vb.put("label", "treatment");
        variants.add(vb);
        expBody.put("variants", variants);

        var resp = controller.createExperiment("token", templateId, expBody);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertNotNull(resp.getBody().get("id"));
    }

    @Test
    void deleteTemplate_removes() {
        var t = controller.createTemplate("token", templateBody("t", 1L, "au"));
        long templateId = ((Number) t.getBody().get("id")).longValue();
        Map<String, Object> out = controller.deleteTemplate("token", templateId);
        assertEquals(true, out.get("deleted"));
        assertTrue(controller.listTemplates("token", "au").stream().noneMatch(x -> ((Number) x.get("id")).longValue() == templateId));
    }

    @Test
    void listTemplates_returnsList() {
        controller.createTemplate("token", templateBody("t1", 1L, "au"));
        controller.createTemplate("token", templateBody("t2", 1L, "au"));
        List<Map<String, Object>> got = controller.listTemplates("token", "au");
        assertEquals(2, got.size());
    }

    private static Map<String, Object> templateBody(String name, long ownerId, String tenant) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", "");
        m.put("ownerId", ownerId);
        m.put("tenantId", tenant);
        return m;
    }

    private static Map<String, Object> versionBody(String userPrompt, long authorId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("systemPrompt", "You are helpful.");
        m.put("userPrompt", userPrompt);
        m.put("model", "gpt-4o");
        m.put("authorId", authorId);
        return m;
    }
}
