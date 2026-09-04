package com.company.agentgateway.infra.persistence.dataset;

import com.company.agentgateway.domain.dataset.EvalCase;
import com.company.agentgateway.domain.dataset.EvalDataset;
import com.company.agentgateway.domain.dataset.EvalRun;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 数据集 / case / run 共享内存存储（spec 2026-09-02 §dataset-eval §3.5 P0）。
 *
 * <p>三个独立 Repo 共享同一 store,保证级联删除一致（deleteDataset 清空 cases + runs）。
 * R14 替换为 PgRepository(dataset / case / run 三张表)。
 */
public class InMemoryDatasetRepositories {

    private final CopyOnWriteArrayList<EvalDataset> datasets = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<EvalCase> cases = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<EvalRun> runs = new CopyOnWriteArrayList<>();
    private final AtomicLong nextDatasetId = new AtomicLong(1);
    private final AtomicLong nextCaseId = new AtomicLong(1);
    private final AtomicLong nextRunId = new AtomicLong(1);

    // ============= Dataset =============

    public EvalDataset saveDataset(EvalDataset d) {
        if (d.id() == 0) {
            long id = nextDatasetId.getAndIncrement();
            EvalDataset p = new EvalDataset(id, d.name(), d.description(),
                    d.tenantId(), d.ownerId(), d.tags(), d.createdAt());
            datasets.add(p);
            return p;
        }
        datasets.removeIf(x -> x.id() == d.id());
        datasets.add(d);
        return d;
    }

    public Optional<EvalDataset> findDatasetById(long id) {
        return datasets.stream().filter(d -> d.id() == id).findFirst();
    }

    public List<EvalDataset> findDatasetsByTenant(String tenantId) {
        return datasets.stream()
                .filter(d -> d.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(EvalDataset::createdAt).reversed())
                .toList();
    }

    public boolean deleteDataset(long id) {
        int before = datasets.size();
        datasets.removeIf(d -> d.id() == id);
        cases.removeIf(c -> c.datasetId() == id);
        runs.removeIf(r -> r.datasetId() == id);
        return before != datasets.size();
    }

    // ============= Case =============

    public EvalCase saveCase(EvalCase c) {
        if (c.id() == 0) {
            long id = nextCaseId.getAndIncrement();
            EvalCase p = new EvalCase(id, c.datasetId(), c.input(),
                    c.expectedOutput(), c.metadata(), c.weight());
            cases.add(p);
            return p;
        }
        cases.removeIf(x -> x.id() == c.id());
        cases.add(c);
        return c;
    }

    public List<EvalCase> findCasesByDataset(long datasetId) {
        return cases.stream().filter(c -> c.datasetId() == datasetId).toList();
    }

    public int countCasesByDataset(long datasetId) {
        return (int) cases.stream().filter(c -> c.datasetId() == datasetId).count();
    }

    // ============= Run =============

    public EvalRun saveRun(EvalRun r) {
        if (r.id() == 0) {
            long id = nextRunId.getAndIncrement();
            EvalRun p = new EvalRun(id, r.datasetId(), r.promptVersionId(),
                    r.model(), r.strategy(), r.status(), r.metrics(), r.results(),
                    r.tenantId(), r.triggeredBy(), r.createdAt(), r.finishedAt());
            runs.add(p);
            return p;
        }
        runs.removeIf(x -> x.id() == r.id());
        runs.add(r);
        return r;
    }

    public Optional<EvalRun> findRunById(long id) {
        return runs.stream().filter(r -> r.id() == id).findFirst();
    }

    public List<EvalRun> findRunsByDataset(long datasetId) {
        return runs.stream()
                .filter(r -> r.datasetId() == datasetId)
                .sorted(Comparator.comparing(EvalRun::createdAt).reversed())
                .toList();
    }
}
