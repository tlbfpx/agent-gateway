package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.application.dataset.DatasetService;
import com.company.agentgateway.application.dataset.EvalRunService;
import com.company.agentgateway.domain.dataset.EvalDataset;
import com.company.agentgateway.domain.dataset.EvalRun;
import com.company.agentgateway.domain.dataset.EvalStrategy;
import com.company.agentgateway.domain.prompt.PromptTemplate;
import com.company.agentgateway.domain.prompt.PromptVersion;
import com.company.agentgateway.infra.persistence.dataset.InMemoryDatasetRepositories;
import com.company.agentgateway.infra.persistence.prompt.InMemoryPromptTemplateRepository;
import com.company.agentgateway.infra.persistence.prompt.InMemoryPromptVersionRepository;
import com.company.agentgateway.infra.persistence.prompt.SharedPromptStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminDatasetControllerTest {

    private AdminDatasetController controller;
    private InMemoryDatasetRepositories store;
    private long promptVersionId;

    @BeforeEach
    void setUp() {
        store = new InMemoryDatasetRepositories();
        SharedPromptStore promptStore = new SharedPromptStore();
        InMemoryPromptVersionRepository versionRepo = new InMemoryPromptVersionRepository(promptStore);
        DatasetService datasetService = new DatasetService(store);
        EvalRunService runService = new EvalRunService(store, versionRepo);
        controller = new AdminDatasetController(datasetService, runService);

        PromptTemplate t = new InMemoryPromptTemplateRepository(promptStore).save(
                PromptTemplate.create("summarize", "x", 1L, "au", null));
        PromptVersion v = versionRepo.save(PromptVersion.create(
                t.id(), 1, "sys", "user", "gpt-4o", null, 7L));
        promptVersionId = v.id();
    }

    @Test
    void createDataset_returns201() {
        var resp = controller.createDataset("token", datasetBody("smoke", 1L, "au"));
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertEquals("smoke", resp.getBody().get("name"));
    }

    @Test
    void importCases_returnsCount() {
        var d = controller.createDataset("token", datasetBody("t", 1L, "au"));
        long id = ((Number) d.getBody().get("id")).longValue();
        String jsonl = "{\"input\":\"a\",\"expected\":\"a\"}\n{\"input\":\"b\",\"expected\":\"b\"}";
        Map<String, Object> out = controller.importCases("token", id, Map.of("jsonl", jsonl));
        assertEquals(2, out.get("imported"));
        assertEquals(2, out.get("caseCount"));
    }

    @Test
    void listCases_returnsImported() {
        var d = controller.createDataset("token", datasetBody("t", 1L, "au"));
        long id = ((Number) d.getBody().get("id")).longValue();
        controller.importCases("token", id, Map.of("jsonl", "{\"input\":\"a\",\"expected\":\"A\"}"));
        List<Map<String, Object>> cases = controller.listCases("token", id);
        assertEquals(1, cases.size());
    }

    @Test
    void runEval_succeeds() {
        var d = controller.createDataset("token", datasetBody("t", 1L, "au"));
        long id = ((Number) d.getBody().get("id")).longValue();
        controller.importCases("token", id, Map.of("jsonl",
                "{\"input\":\"a\",\"expected\":\"a\"}\n{\"input\":\"b\",\"expected\":\"b\"}"));

        Map<String, Object> runBody = new LinkedHashMap<>();
        runBody.put("promptVersionId", promptVersionId);
        runBody.put("model", "gpt-4o");
        runBody.put("strategy", "CONTAINS");
        runBody.put("triggeredBy", 7L);

        var resp = controller.runEval("token", id, runBody);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertNotNull(resp.getBody().get("id"));
        assertEquals("COMPLETED", resp.getBody().get("status"));
    }

    @Test
    void getRun_returnsDetails() {
        var d = controller.createDataset("token", datasetBody("t", 1L, "au"));
        long id = ((Number) d.getBody().get("id")).longValue();
        controller.importCases("token", id, Map.of("jsonl", "{\"input\":\"a\",\"expected\":\"a\"}"));

        Map<String, Object> runBody = new LinkedHashMap<>();
        runBody.put("promptVersionId", promptVersionId);
        runBody.put("model", "gpt-4o");
        runBody.put("strategy", "CONTAINS");
        runBody.put("triggeredBy", 7L);
        var resp = controller.runEval("token", id, runBody);
        long runId = ((Number) resp.getBody().get("id")).longValue();

        Map<String, Object> got = controller.getRun("token", runId);
        assertEquals("COMPLETED", got.get("status"));
        Map<String, Object> metrics = (Map<String, Object>) got.get("metrics");
        assertEquals(1, metrics.get("total"));
        assertEquals(1, metrics.get("passed"));
    }

    @Test
    void deleteDataset_cascades() {
        var d = controller.createDataset("token", datasetBody("t", 1L, "au"));
        long id = ((Number) d.getBody().get("id")).longValue();
        controller.importCases("token", id, Map.of("jsonl", "{\"input\":\"a\",\"expected\":\"a\"}"));
        Map<String, Object> out = controller.deleteDataset("token", id);
        assertEquals(true, out.get("deleted"));
        assertTrue(controller.listDatasets("token", "au").stream().noneMatch(x -> ((Number) x.get("id")).longValue() == id));
    }

    @Test
    void rejectsInvalidStrategy() {
        var d = controller.createDataset("token", datasetBody("t", 1L, "au"));
        long id = ((Number) d.getBody().get("id")).longValue();
        controller.importCases("token", id, Map.of("jsonl", "{\"input\":\"a\",\"expected\":\"a\"}"));
        Map<String, Object> runBody = new LinkedHashMap<>();
        runBody.put("promptVersionId", promptVersionId);
        runBody.put("model", "gpt-4o");
        runBody.put("strategy", "FUZZY");
        runBody.put("triggeredBy", 7L);
        assertThrows(ResponseStatusException.class, () -> controller.runEval("token", id, runBody));
    }

    @Test
    void rejectsMissingToken() {
        assertThrows(ResponseStatusException.class,
                () -> controller.createDataset(null, datasetBody("t", 1L, "au")));
    }

    private static Map<String, Object> datasetBody(String name, long ownerId, String tenant) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", "test");
        m.put("ownerId", ownerId);
        m.put("tenantId", tenant);
        return m;
    }
}
