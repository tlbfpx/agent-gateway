package com.company.agentgateway.infra.persistence.workflow;

import com.company.agentgateway.domain.workflow.WorkflowDefinition;
import com.company.agentgateway.domain.workflow.WorkflowDefinitionRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * WorkflowDefinitionRepository PG 实现(spec C1 §8 扩展):body 直接存 text,
 * format 字段区分 JSON/YAML;普通表(无 hypertable 需要)。
 */
public class PgWorkflowDefinitionRepository implements WorkflowDefinitionRepository {

    private final JdbcTemplate jdbc;

    public PgWorkflowDefinitionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public WorkflowDefinition save(WorkflowDefinition def) {
        jdbc.update("""
                INSERT INTO workflow_definitions (name, description, body, format, created_at, updated_at, created_by)
                VALUES (?, ?, ?, ?, COALESCE(?, now()), now(), ?)
                ON CONFLICT (name) DO UPDATE SET
                    description = EXCLUDED.description,
                    body = EXCLUDED.body,
                    format = EXCLUDED.format,
                    updated_at = now(),
                    created_by = EXCLUDED.created_by
                """,
                def.name(), def.description(), def.body(),
                def.format() == null ? "json" : def.format().name().toLowerCase(),
                def.createdAt() == null ? null : Timestamp.from(def.createdAt()),
                def.createdBy());
        return find(def.name()).orElseThrow();
    }

    @Override
    public Optional<WorkflowDefinition> find(String name) {
        List<WorkflowDefinition> r = jdbc.query(
                "SELECT name, description, body, format, created_at, updated_at, created_by " +
                "FROM workflow_definitions WHERE name = ?",
                (rs, i) -> new WorkflowDefinition(
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("body"),
                        WorkflowDefinition.Format.valueOf(rs.getString("format").toUpperCase()),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getString("created_by")),
                name);
        return r.stream().findFirst();
    }

    @Override
    public List<WorkflowDefinition> listAll() {
        return jdbc.query(
                "SELECT name, description, body, format, created_at, updated_at, created_by " +
                "FROM workflow_definitions ORDER BY updated_at DESC",
                (rs, i) -> new WorkflowDefinition(
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("body"),
                        WorkflowDefinition.Format.valueOf(rs.getString("format").toUpperCase()),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getString("created_by")));
    }

    @Override
    public boolean delete(String name) {
        return jdbc.update("DELETE FROM workflow_definitions WHERE name = ?", name) > 0;
    }
}