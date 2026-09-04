package com.company.agentgateway.application.dataset;

import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.dataset.EvalCase;
import com.company.agentgateway.domain.dataset.EvalCaseResult;
import com.company.agentgateway.domain.dataset.EvalDataset;
import com.company.agentgateway.domain.dataset.EvalRun;
import com.company.agentgateway.domain.dataset.EvalStrategy;
import com.company.agentgateway.domain.dataset.Judge;
import com.company.agentgateway.domain.prompt.PromptVersion;
import com.company.agentgateway.domain.prompt.PromptVersionRepository;
import com.company.agentgateway.infra.persistence.dataset.InMemoryDatasetRepositories;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 评测运行用例层（spec 2026-09-02 §dataset-eval §5.2）。
 *
 * <p>P0 同步执行规则评测:对每个 case 应用 strategy.pass(actual, expected)。
 * 真实 LLM 调用 R14 接 ChatOrchestrator;P0 用 stub 函数模拟输出(expected + " (stub)")。
 */
public class EvalRunService {

    private static final Logger log = LoggerFactory.getLogger(EvalRunService.class);

    private final InMemoryDatasetRepositories store;
    private final PromptVersionRepository promptRepo;
    private final Judge judge;

    public EvalRunService(InMemoryDatasetRepositories store, PromptVersionRepository promptRepo) {
        this(store, promptRepo, null);
    }

    public EvalRunService(InMemoryDatasetRepositories store, PromptVersionRepository promptRepo, Judge judge) {
        this.store = store;
        this.promptRepo = promptRepo;
        this.judge = judge;
    }

    public EvalRun run(long datasetId, long promptVersionId, String model,
                       EvalStrategy strategy, long triggeredBy, AdminRole callerRole) {
        requireOperator(callerRole);
        EvalDataset dataset = store.findDatasetById(datasetId)
                .orElseThrow(() -> new IllegalArgumentException("dataset not found: " + datasetId));
        Optional<PromptVersion> pv = promptRepo.findById(promptVersionId);
        if (pv.isEmpty()) {
            throw new IllegalArgumentException("prompt version not found: " + promptVersionId);
        }
        List<EvalCase> cases = store.findCasesByDataset(datasetId);
        if (cases.isEmpty()) {
            throw new IllegalStateException("dataset has no cases");
        }
        if (cases.size() > 1000) {
            throw new IllegalStateException("dataset too large for P0 sync run: " + cases.size()
                    + " (limit 1000)");
        }

        long now = System.currentTimeMillis();
        EvalRun pending = new EvalRun(0L, datasetId, promptVersionId, model, strategy,
                EvalRun.Status.RUNNING, EvalRun.RunMetrics.empty(), List.of(),
                dataset.tenantId(), triggeredBy, Instant.ofEpochMilli(now), null);
        EvalRun started = store.saveRun(pending);

        // 同步评测每个 case
        List<EvalCaseResult> results = new ArrayList<>();
        int passed = 0;
        long totalLatency = 0;
        for (EvalCase c : cases) {
            long t0 = System.currentTimeMillis();
            String actual = simulateActualOutput(c, pv.get(), model);
            long latency = System.currentTimeMillis() - t0 + 50; // +50ms baseline
            boolean ok;
            double score;
            if (strategy == EvalStrategy.LLM_AS_JUDGE) {
                Judge j = judge;
                if (j == null) throw new IllegalStateException(
                        "Judge required for LLM_AS_JUDGE strategy; configure one in Spring");
                Judge.Verdict v = j.judge(c.input(), c.expectedOutput(), actual,
                        "判定 expected 与 actual 语义是否等价");
                ok = v.pass();
                score = v.confidence();
                latency += 100; // LLM 评判模拟延迟 +100ms
            } else {
                ok = strategy.pass(actual, c.expectedOutput());
                score = ok ? 1.0 : 0.0;
            }
            if (ok) passed++;
            totalLatency += latency;
            results.add(new EvalCaseResult(c.id(), actual, ok, score, latency));
        }
        int total = cases.size();
        double passRate = total == 0 ? 0 : (double) passed / total;
        double avgLatency = total == 0 ? 0 : (double) totalLatency / total;

        EvalRun.RunMetrics metrics = new EvalRun.RunMetrics(total, passed, passRate, avgLatency);
        EvalRun completed = new EvalRun(started.id(), datasetId, promptVersionId, model,
                strategy, EvalRun.Status.COMPLETED, metrics, results,
                dataset.tenantId(), triggeredBy, started.createdAt(), Instant.now());
        EvalRun saved = store.saveRun(completed);
        log.info("dataset.eval.run id={} dataset={} cases={} passed={} passRate={}",
                saved.id(), datasetId, total, passed, passRate);
        return saved;
    }

    /** P0 stub 模拟：基于 expected + " (model:" + model + ")" */
    private static String simulateActualOutput(EvalCase c, PromptVersion pv, String model) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.expectedOutput());
        sb.append(" [model:").append(model).append("]");
        sb.append(" [pv:").append(pv.version()).append("]");
        return sb.toString();
    }

    public EvalRun findById(long id) {
        return store.findRunById(id)
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + id));
    }

    public List<EvalRun> findByDataset(long datasetId) {
        return store.findRunsByDataset(datasetId);
    }

    private static void requireOperator(AdminRole caller) {
        if (caller == null || !caller.atLeast(AdminRole.OPERATOR)) {
            throw new SecurityException("caller role " + caller + " insufficient (need OPERATOR)");
        }
    }
}
