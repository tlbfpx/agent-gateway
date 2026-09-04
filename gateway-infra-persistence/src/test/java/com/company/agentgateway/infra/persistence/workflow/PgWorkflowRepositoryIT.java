package com.company.agentgateway.infra.persistence.workflow;

import com.company.agentgateway.domain.workflow.StepRun;
import com.company.agentgateway.domain.workflow.WorkflowRun;
import com.company.agentgateway.infra.persistence.observability.TestDb;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// 复用 TestDb 共享 PG 容器(同模块 P0);不单独起容器,避免与 PgObservabilityStoresIT 冲突。

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PgWorkflowRepository 集成测试(spec C1 §3.4 P1):save + find 闭环,
 * 步骤 inputs/outputs JSONB 序列化 + 反序列化。
 */
class PgWorkflowRepositoryIT {

    private static JdbcTemplate jdbc;
    private static PgWorkflowRepository repo;

    @BeforeAll
    static void init() {
        DataSource ds = TestDb.connect();  // 见 TestDb 内部:单例 PG 容器(共享)
        // 仅 CREATE 自身关心的表(假设 schema 已存在,失败时 CREATE IF NOT EXISTS 兜底)
        new JdbcTemplate(ds).execute("""
                CREATE TABLE IF NOT EXISTS workflow_runs (
                    run_id           varchar(64)  PRIMARY KEY,
                    workflow_name    varchar(256) NOT NULL,
                    status           varchar(16)  NOT NULL,
                    started_at       timestamptz  NOT NULL,
                    finished_at      timestamptz,
                    start_time       timestamptz  NOT NULL DEFAULT now()
                )""");
        // hypertable 创建:if_not_exists = TRUE
        try {
            new JdbcTemplate(ds).execute("SELECT create_hypertable('workflow_runs', 'start_time', if_not_exists => TRUE)");
        } catch (Exception ignore) { /* 普通 PG 时跳过 */ }
        new JdbcTemplate(ds).execute("""
                CREATE TABLE IF NOT EXISTS workflow_run_steps (
                    run_id           varchar(64)  NOT NULL,
                    step_index       integer      NOT NULL,
                    step_name        varchar(128) NOT NULL,
                    status           varchar(16)  NOT NULL,
                    inputs           jsonb        NOT NULL DEFAULT '{}'::jsonb,
                    outputs          jsonb        NOT NULL DEFAULT '{}'::jsonb,
                    duration_ms      bigint,
                    error_message    text,
                    created_at       timestamptz  NOT NULL DEFAULT now(),
                    PRIMARY KEY (run_id, step_index)
                )""");
        new JdbcTemplate(ds).execute("DELETE FROM workflow_run_steps");
        new JdbcTemplate(ds).execute("DELETE FROM workflow_runs");
        jdbc = new JdbcTemplate(ds);
        repo = new PgWorkflowRepository(jdbc, new ObjectMapper());
    }

    @Test
    void save与find_完整steps闭环() {
        Instant now = Instant.now();
        WorkflowRun run = new WorkflowRun(
                "run-it-1", "rag-summary", WorkflowRun.Status.COMPLETED,
                now, now.plusSeconds(2), Map.of(),
                List.of(
                        new StepRun("retrieve", StepRun.Status.COMPLETED,
                                Map.of("query", "hi"),
                                Map.of("chunks", List.of("c1", "c2")),
                                120L, null),
                        new StepRun("summarize", StepRun.Status.COMPLETED,
                                Map.of("context", List.of("c1", "c2")),
                                Map.of("summary", "hello"),
                                80L, null)
                ));
        repo.save(run);

        Optional<WorkflowRun> fetched = repo.find("run-it-1");
        assertThat(fetched).isPresent();
        WorkflowRun r = fetched.get();
        assertThat(r.runId()).isEqualTo("run-it-1");
        assertThat(r.workflowName()).isEqualTo("rag-summary");
        assertThat(r.status()).isEqualTo(WorkflowRun.Status.COMPLETED);
        assertThat(r.finishedAt()).isNotNull();
        assertThat(r.steps()).hasSize(2);
        // 步骤 JSONB 序列化反序列化
        assertThat(r.steps().get(0).name()).isEqualTo("retrieve");
        assertThat(r.steps().get(0).outputs()).containsEntry("chunks", List.of("c1", "c2"));
        assertThat(r.steps().get(0).durationMs()).isEqualTo(120L);
        assertThat(r.steps().get(1).name()).isEqualTo("summarize");
        assertThat(r.steps().get(1).outputs()).containsEntry("summary", "hello");
    }

    @Test
    void save_upsert覆盖语义() {
        Instant now = Instant.now();
        WorkflowRun first = new WorkflowRun("run-it-2", "x", WorkflowRun.Status.RUNNING,
                now, null, Map.of(), List.of());
        repo.save(first);
        WorkflowRun second = new WorkflowRun("run-it-2", "x", WorkflowRun.Status.COMPLETED,
                now, now.plusSeconds(1), Map.of(), List.of());
        repo.save(second);

        WorkflowRun r = repo.find("run-it-2").orElseThrow();
        assertThat(r.status()).isEqualTo(WorkflowRun.Status.COMPLETED);
        assertThat(r.finishedAt()).isNotNull();
    }

    @Test
    void 失败step带errorMessage_持久化与读出() {
        Instant now = Instant.now();
        WorkflowRun run = new WorkflowRun("run-it-3", "failing", WorkflowRun.Status.FAILED,
                now, now.plusMillis(500), Map.of(), List.of(
                        new StepRun("boom", StepRun.Status.FAILED,
                                Map.of("q", "x"), Map.of(), 100L, "agent boom")
                ));
        repo.save(run);
        WorkflowRun r = repo.find("run-it-3").orElseThrow();
        assertThat(r.status()).isEqualTo(WorkflowRun.Status.FAILED);
        assertThat(r.steps().get(0).errorMessage()).isEqualTo("agent boom");
    }

    @Test
    void 查找不存在的runId返回空() {
        assertThat(repo.find("non-existent")).isEmpty();
    }
}