package com.company.agentgateway.domain.workflow;

import java.util.List;
import java.util.Optional;

/**
 * WorkflowDefinition 仓储(spec C1 §8 扩展):CRUD + 按 name 查找 + 按更新时间列表。
 * 与 WorkflowRepository 独立:定义可复用,运行实例按次记录。
 */
public interface WorkflowDefinitionRepository {

    WorkflowDefinition save(WorkflowDefinition def);

    Optional<WorkflowDefinition> find(String name);

    List<WorkflowDefinition> listAll();

    boolean delete(String name);
}