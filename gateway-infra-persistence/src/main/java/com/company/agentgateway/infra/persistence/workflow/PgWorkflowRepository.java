package com.company.agentgateway.infra.persistence.workflow;

import com.company.agentgateway.domain.workflow.StepRun;
import com.company.agentgateway.domain.workflow.WorkflowRepository;
import com.company.agentgateway.domain.workflow.WorkflowRun;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * WorkflowRepository PG 实现(spec C1 §3.4 P1):跨重启持久化。
 * 表 schema:workflow_runs(顶层)+ workflow_run_steps(子 step,JSONB 存 inputs/outputs)。
 * 降级:未配置 PG(无 jdbc-url)时 InMemoryWorkflowRepository 由 application 装配。
 */
public class PgWorkflowRepository implements WorkflowRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PgWorkflowRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public WorkflowRun save(WorkflowRun run) {
        // 顶层
        jdbc.update("""
                INSERT INTO workflow_runs (run_id, workflow_name, status, started_at, finished_at, start_time)
                VALUES (?, ?, ?, ?, ?, COALESCE(?, now()))
                ON CONFLICT (run_id) DO UPDATE SET
                    workflow_name = EXCLUDED.workflow_name,
                    status = EXCLUDED.status,
                    started_at = EXCLUDED.started_at,
                    finished_at = EXCLUDED.finished_at
                """,
                run.runId(), run.workflowName(), run.status().name(),
                Timestamp.from(run.startedAt()),
                run.finishedAt() == null ? null : Timestamp.from(run.finishedAt()),
                run.startedAt() == null ? null : Timestamp.from(run.startedAt()));
        // 子 step 全量替换(简单可靠;workflow run 完成后 steps 不再变化)
        jdbc.update("DELETE FROM workflow_run_steps WHERE run_id = ?", run.runId());
        int idx = 0;
        for (StepRun s : run.steps()) {
            jdbc.update("""
                    INSERT INTO workflow_run_steps
                        (run_id, step_index, step_name, status, inputs, outputs, duration_ms, error_message, created_at)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, COALESCE(?, now()))
                    """,
                    run.runId(), idx++, s.name(), s.status().name(),
                    toJson(s.inputs()),
                    toJson(s.outputs()),
                    s.durationMs(),
                    s.errorMessage(),
                    run.startedAt() == null ? null : Timestamp.from(run.startedAt()));
        }
        return run;
    }

    @Override
    public List<WorkflowRun> list(ListFilter filter, int limit, int offset) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (filter.workflowName() != null && !filter.workflowName().isBlank()) {
            where.append(" AND workflow_name = ?");
            args.add(filter.workflowName());
        }
        if (filter.status() != null && !filter.status().isBlank()) {
            where.append(" AND status = ?");
            args.add(filter.status());
        }
        if (filter.from() != null) {
            where.append(" AND started_at >= ?");
            args.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            where.append(" AND started_at < ?");
            args.add(Timestamp.from(filter.to()));
        }
        where.append(" ORDER BY started_at DESC LIMIT ? OFFSET ?");
        args.add(limit > 0 ? limit : 50);
        args.add(Math.max(offset, 0));
        return jdbc.query(
                "SELECT run_id, workflow_name, status, started_at, finished_at FROM workflow_runs"
                        + where,
                (rs, i) -> new WorkflowRun(
                        rs.getString("run_id"),
                        rs.getString("workflow_name"),
                        WorkflowRun.Status.valueOf(rs.getString("status")),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                        Map.of(),
                        List.of()),
                args.toArray());
    }

    @Override
    public Optional<WorkflowRun> find(String runId) {
        List<WorkflowRun> tops = jdbc.query(
                "SELECT run_id, workflow_name, status, started_at, finished_at FROM workflow_runs WHERE run_id = ?",
                (rs, i) -> new WorkflowRun(
                        rs.getString("run_id"),
                        rs.getString("workflow_name"),
                        WorkflowRun.Status.valueOf(rs.getString("status")),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                        Map.of(),
                        List.of()
                ),
                runId);
        if (tops.isEmpty()) return Optional.empty();
        WorkflowRun top = tops.get(0);
        // 加载子 steps
        List<StepRun> steps = jdbc.query("""
                SELECT step_name, status, inputs, outputs, duration_ms, error_message
                FROM workflow_run_steps WHERE run_id = ? ORDER BY step_index
                """,
                (rs, i) -> new StepRun(
                        rs.getString("step_name"),
                        StepRun.Status.valueOf(rs.getString("status")),
                        readMap(rs.getString("inputs")),
                        readMap(rs.getString("outputs")),
                        rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"),
                        rs.getString("error_message")
                ),
                runId);
        return Optional.of(new WorkflowRun(top.runId(), top.workflowName(), top.status(),
                top.startedAt(), top.finishedAt(), Map.of(), steps));
    }

    private String toJson(Map<String, Object> m) {
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String s) {
        try {
            return objectMapper.readValue(s == null ? "{}" : s, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}