package com.company.agentgateway.application.workflow.repo;

import com.company.agentgateway.domain.workflow.WorkflowDefinition;
import com.company.agentgateway.domain.workflow.WorkflowDefinitionRepository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * WorkflowDefinitionRepository 首期实现:内存 ConcurrentHashMap(同 SessionRepository 风格)。
 */
public class InMemoryWorkflowDefinitionRepository implements WorkflowDefinitionRepository {

    private final ConcurrentHashMap<String, WorkflowDefinition> store = new ConcurrentHashMap<>();

    @Override
    public WorkflowDefinition save(WorkflowDefinition def) {
        Instant now = Instant.now();
        WorkflowDefinition persisted = new WorkflowDefinition(
                def.name(), def.description(), def.body(), def.format(),
                def.createdAt() == null ? now : def.createdAt(),
                now, def.createdBy());
        store.put(def.name(), persisted);
        return persisted;
    }

    @Override
    public Optional<WorkflowDefinition> find(String name) {
        return Optional.ofNullable(store.get(name));
    }

    @Override
    public List<WorkflowDefinition> listAll() {
        return store.values().stream()
                .sorted(Comparator.comparing(WorkflowDefinition::updatedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public boolean delete(String name) {
        return store.remove(name) != null;
    }
}