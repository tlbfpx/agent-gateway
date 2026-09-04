package com.company.agentgateway.application.workflow.repo;

import com.company.agentgateway.domain.workflow.WorkflowRepository;
import com.company.agentgateway.domain.workflow.WorkflowRun;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * WorkflowRepository 首期实现(spec C1 §3.4 + 列表 API):内存 ConcurrentHashMap,跨重启丢数据。
 */
public class InMemoryWorkflowRepository implements WorkflowRepository {

    private final ConcurrentHashMap<String, WorkflowRun> store = new ConcurrentHashMap<>();

    @Override
    public WorkflowRun save(WorkflowRun run) {
        store.put(run.runId(), run);
        return run;
    }

    @Override
    public Optional<WorkflowRun> find(String runId) {
        return Optional.ofNullable(store.get(runId));
    }

    @Override
    public List<WorkflowRun> list(ListFilter filter, int limit, int offset) {
        return store.values().stream()
                .filter(r -> filter.workflowName() == null || r.workflowName().equals(filter.workflowName()))
                .filter(r -> filter.status() == null || r.status().name().equals(filter.status()))
                .filter(r -> filter.from() == null || !r.startedAt().isBefore(filter.from()))
                .filter(r -> filter.to() == null || r.startedAt().isBefore(filter.to()))
                .sorted(Comparator.comparing(WorkflowRun::startedAt).reversed())
                .skip(Math.max(offset, 0))
                .limit(Math.max(limit, 0) == 0 ? 50 : Math.max(limit, 0))
                .collect(Collectors.toList());
    }
}