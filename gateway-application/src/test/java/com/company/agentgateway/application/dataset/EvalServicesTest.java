package com.company.agentgateway.application.dataset;

import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.dataset.EvalCase;
import com.company.agentgateway.domain.dataset.EvalDataset;
import com.company.agentgateway.domain.dataset.EvalRun;
import com.company.agentgateway.domain.dataset.EvalStrategy;
import com.company.agentgateway.domain.prompt.PromptTemplate;
import com.company.agentgateway.domain.prompt.PromptVersion;
import com.company.agentgateway.infra.persistence.dataset.InMemoryDatasetRepositories;
import com.company.agentgateway.infra.persistence.prompt.InMemoryPromptTemplateRepository;
import com.company.agentgateway.infra.persistence.prompt.InMemoryPromptVersionRepository;
import com.company.agentgateway.infra.persistence.prompt.SharedPromptStore;
import com.company.agentgateway.infra.persistence.judge.StubJudge;
import com.company.agentgateway.domain.dataset.Judge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalServicesTest {

    private InMemoryDatasetRepositories store;
    private InMemoryPromptVersionRepository versionRepo;
    private DatasetService datasetService;
    private EvalRunService runService;
    private long promptVersionId;

    @BeforeEach
    void setUp() {
        store = new InMemoryDatasetRepositories();
        SharedPromptStore promptStore = new SharedPromptStore();
        versionRepo = new InMemoryPromptVersionRepository(promptStore);
        datasetService = new DatasetService(store);
        runService = new EvalRunService(store, versionRepo);
        // 准备一个 prompt version
        PromptTemplate t = new InMemoryPromptTemplateRepository(promptStore).save(
                PromptTemplate.create("summarize", "x", 1L, "au", null));
        PromptVersion v = versionRepo.save(PromptVersion.create(
                t.id(), 1, "sys", "user", "gpt-4o", null, 7L));
        promptVersionId = v.id();
    }

    @Test
    void createDataset_adminCanCreate() {
        EvalDataset d = datasetService.create("smoke", "x", "au", 1L, null, AdminRole.ADMIN);
        assertNotNull(d.id());
    }

    @Test
    void createDataset_viewerRejected() {
        assertThrows(SecurityException.class, () ->
                datasetService.create("x", "x", "au", 1L, null, AdminRole.VIEWER));
    }

    @Test
    void importJsonl_parsesValidCases() {
        EvalDataset d = datasetService.create("t", "x", "au", 1L, null, AdminRole.ADMIN);
        String jsonl = """
                {"input":"a","expected":"A","weight":2}
                {"input":"b","expected":"B"}
                # comment line
                {"input":"c","expected":"C"}
                """;
        int imported = datasetService.importJsonl(d.id(), jsonl, AdminRole.OPERATOR);
        assertEquals(3, imported);
        assertEquals(3, datasetService.countCases(d.id()));
    }

    @Test
    void importJsonl_skipsMalformed() {
        EvalDataset d = datasetService.create("t", "x", "au", 1L, null, AdminRole.ADMIN);
        String jsonl = """
                {"input":"a","expected":"A"}
                not-json-at-all
                {"input":"b","expected":"B"}
                """;
        int imported = datasetService.importJsonl(d.id(), jsonl, AdminRole.OPERATOR);
        assertEquals(2, imported);
    }

    @Test
    void importJsonl_rejectsEmpty() {
        EvalDataset d = datasetService.create("t", "x", "au", 1L, null, AdminRole.ADMIN);
        assertThrows(IllegalArgumentException.class, () ->
                datasetService.importJsonl(d.id(), "", AdminRole.OPERATOR));
    }

    @Test
    void runEval_executesAndReports() {
        EvalDataset d = datasetService.create("t", "x", "au", 1L, null, AdminRole.ADMIN);
        String jsonl = """
                {"input":"a","expected":"a"}
                {"input":"b","expected":"b"}
                {"input":"c","expected":"c"}
                """;
        datasetService.importJsonl(d.id(), jsonl, AdminRole.OPERATOR);
        EvalRun run = runService.run(d.id(), promptVersionId, "gpt-4o",
                EvalStrategy.CONTAINS, 1L, AdminRole.OPERATOR);
        assertEquals(EvalRun.Status.COMPLETED, run.status());
        assertEquals(3, run.metrics().total());
        // stub 输出 "a [model:gpt-4o] [pv:1]" CONTAINS "a" → true
        assertEquals(3, run.metrics().passed());
        assertEquals(1.0, run.metrics().passRate(), 0.001);
    }

    @Test
    void runEval_rejectsEmptyDataset() {
        EvalDataset d = datasetService.create("t", "x", "au", 1L, null, AdminRole.ADMIN);
        assertThrows(IllegalStateException.class, () ->
                runService.run(d.id(), promptVersionId, "gpt-4o",
                        EvalStrategy.EXACT, 1L, AdminRole.OPERATOR));
    }

    @Test
    void runEval_rejectsMissingPromptVersion() {
        EvalDataset d = datasetService.create("t", "x", "au", 1L, null, AdminRole.ADMIN);
        datasetService.importJsonl(d.id(), "{\"input\":\"a\",\"expected\":\"a\"}", AdminRole.OPERATOR);
        assertThrows(IllegalArgumentException.class, () ->
                runService.run(d.id(), 99999L, "gpt-4o",
                        EvalStrategy.EXACT, 1L, AdminRole.OPERATOR));
    }

    @Test
    void runEval_viewerRejected() {
        EvalDataset d = datasetService.create("t", "x", "au", 1L, null, AdminRole.ADMIN);
        assertThrows(SecurityException.class, () ->
                runService.run(d.id(), promptVersionId, "gpt-4o",
                        EvalStrategy.EXACT, 1L, AdminRole.VIEWER));
    }

    @Test
    void runEval_llmAsJudge_usesJudgePort() {
        EvalDataset d = datasetService.create("t", "x", "au", 1L, null, AdminRole.ADMIN);
        datasetService.importJsonl(d.id(), "{\"input\":\"what is 2+2\",\"expected\":\"4\"}",
                AdminRole.OPERATOR);
        EvalRunService serviceWithJudge = new EvalRunService(store, versionRepo, new StubJudge());
        EvalRun run = serviceWithJudge.run(d.id(), promptVersionId, "gpt-4o",
                EvalStrategy.LLM_AS_JUDGE, 1L, AdminRole.OPERATOR);
        // stub 把 "4 [model:gpt-4o] [pv:1]" 判为含 "4",PASS
        assertEquals(1, run.metrics().passed());
    }

    @Test
    void runEval_llmAsJudge_missingJudge_throws() {
        EvalDataset d = datasetService.create("t", "x", "au", 1L, null, AdminRole.ADMIN);
        datasetService.importJsonl(d.id(), "{\"input\":\"x\",\"expected\":\"y\"}",
                AdminRole.OPERATOR);
        assertThrows(IllegalStateException.class, () ->
                runService.run(d.id(), promptVersionId, "gpt-4o",
                        EvalStrategy.LLM_AS_JUDGE, 1L, AdminRole.OPERATOR));
    }

    @Test
    void delete_cascadesCasesAndRuns() {
        EvalDataset d = datasetService.create("t", "x", "au", 1L, null, AdminRole.ADMIN);
        datasetService.importJsonl(d.id(), "{\"input\":\"a\",\"expected\":\"a\"}", AdminRole.OPERATOR);
        runService.run(d.id(), promptVersionId, "gpt-4o", EvalStrategy.EXACT, 1L, AdminRole.OPERATOR);
        assertTrue(datasetService.delete(d.id(), AdminRole.OWNER));
        assertEquals(0, datasetService.countCases(d.id()));
    }
}
