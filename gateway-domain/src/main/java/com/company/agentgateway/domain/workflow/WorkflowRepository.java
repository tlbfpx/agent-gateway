package com.company.agentgateway.domain.workflow;

import java.util.List;
import java.util.Optional;

/**
 * 工作流执行记录仓储(spec C1 §3.4 + 列表 API):首期 InMemory 实现,后续可替换为 PG。
 * 出站端口由 application / infra 实现。
 */
public interface WorkflowRepository {

    WorkflowRun save(WorkflowRun run);

    Optional<WorkflowRun> find(String runId);

    /**
     * 列表查询(spec P0):按时间倒序,支持 workflowName/status 过滤(均为可选)。
     * limit/offset 分页;latest-first 排序便于 UI 历史页默认展示。
     */
    List<WorkflowRun> list(ListFilter filter, int limit, int offset);

    /** 列表过滤条件(任何字段为 null 表示不过滤)。 */
    record ListFilter(String workflowName, String status, java.time.Instant from, java.time.Instant to) {}
}