-- 可观测性存储 schema(spec 2026-08-19 §4)
-- 由 PgSchemaInitializer 在启动时幂等执行(存在性检查,不重复建)。
-- 时序表(spans/metrics_samples/alerts/audit_events)为 hypertable;alert_rules 为普通表。

CREATE EXTENSION IF NOT EXISTS timescaledb;

-- ============ §4.1 spans(调用链,保留 7 天) ============
CREATE TABLE IF NOT EXISTS spans (
    trace_id        varchar(32)  NOT NULL,
    span_id         varchar(16)  NOT NULL,
    parent_span_id  varchar(16),
    name            varchar(256) NOT NULL,
    kind            varchar(16)  NOT NULL,
    start_time      timestamptz  NOT NULL,
    end_time        timestamptz,
    duration_ms     double precision,
    status          varchar(16)  NOT NULL,
    attributes      jsonb        NOT NULL DEFAULT '{}',
    events          jsonb        NOT NULL DEFAULT '[]',
    PRIMARY KEY (trace_id, span_id, start_time)
);
SELECT create_hypertable('spans', 'start_time', if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS idx_spans_start ON spans (start_time DESC);
CREATE INDEX IF NOT EXISTS idx_spans_name_start ON spans (name, start_time DESC);
CREATE INDEX IF NOT EXISTS idx_spans_attributes ON spans USING gin (attributes);

-- ============ §4.2 metrics_samples(指标时序,原始保留 14 天) ============
CREATE TABLE IF NOT EXISTS metrics_samples (
    metric_name  varchar(128)     NOT NULL,
    tags         jsonb            NOT NULL DEFAULT '{}',
    ts           timestamptz      NOT NULL,
    value        double precision NOT NULL
);
SELECT create_hypertable('metrics_samples', 'ts', if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS idx_metrics_name_ts ON metrics_samples (metric_name, ts DESC);

-- 5 分钟 rollup(§4.2):固定维度展开为列后 group by;请求/错误/token/cost 用 sum,延迟用 avg+max
CREATE MATERIALIZED VIEW IF NOT EXISTS metrics_rollup_5m
WITH (timescaledb.continuous) AS
SELECT metric_name,
       tags ->> 'tenant_id'   AS tenant_id,
       tags ->> 'model'       AS model,
       tags ->> 'provider'    AS provider,
       tags ->> 'agent_name'  AS agent_name,
       time_bucket('5 minutes', ts) AS bucket,
       sum(value)  AS value_sum,
       avg(value)  AS value_avg,
       max(value)  AS value_max,
       count(*)    AS sample_count
FROM metrics_samples
GROUP BY metric_name, tags ->> 'tenant_id', tags ->> 'model', tags ->> 'provider',
         tags ->> 'agent_name', time_bucket('5 minutes', ts)
WITH NO DATA;

-- ============ §4.3 alert_rules(普通表) / alerts(hypertable) ============
CREATE TABLE IF NOT EXISTS alert_rules (
    id              varchar(64)  PRIMARY KEY,
    name            varchar(256) NOT NULL,
    metric_name     varchar(128) NOT NULL,
    operator        varchar(8)   NOT NULL,           -- GT | LT | GTE | LTE
    threshold       double precision NOT NULL,
    window_seconds  integer      NOT NULL DEFAULT 300,
    silence_minutes integer      NOT NULL DEFAULT 30,
    dedup_key_tpl   varchar(256) NOT NULL DEFAULT '{rule}:{metric}',
    severity        varchar(16)  NOT NULL DEFAULT 'warning',
    enabled         boolean      NOT NULL DEFAULT TRUE,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS alerts (
    id                 varchar(64)  NOT NULL,
    rule_id            varchar(64)  NOT NULL,
    severity           varchar(16)  NOT NULL,
    state              varchar(16)  NOT NULL,         -- firing | resolved
    dedup_key          varchar(256) NOT NULL,
    labels             jsonb        NOT NULL DEFAULT '{}',
    first_fired_at     timestamptz  NOT NULL,
    recently_triggered_at timestamptz NOT NULL,
    trigger_count      integer      NOT NULL DEFAULT 1,
    observed_value     double precision,
    threshold          double precision,
    claimed_by         varchar(128),
    note               text,
    resolved_at        timestamptz,
    start_time         timestamptz   NOT NULL DEFAULT now(),  -- hypertable 时间列
    PRIMARY KEY (id, start_time)
);
SELECT create_hypertable('alerts', 'start_time', if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS idx_alerts_state_start ON alerts (state, start_time DESC);
CREATE INDEX IF NOT EXISTS idx_alerts_dedup ON alerts (dedup_key, start_time DESC);

-- ============ §4.4 audit_events(审计迁库,append-only) ============
CREATE TABLE IF NOT EXISTS audit_events (
    event_id       varchar(64)  NOT NULL,
    tenant         varchar(128) NOT NULL,
    actor          varchar(256),
    actor_type     varchar(16)  NOT NULL,
    event_type     varchar(64)  NOT NULL,
    ts             timestamptz  NOT NULL,
    resource_type  varchar(128),
    resource_id    varchar(256),
    action         varchar(128),
    result         varchar(16)  NOT NULL,
    error_message  text,
    start_time     timestamptz  NOT NULL DEFAULT now(),  -- hypertable 时间列
    PRIMARY KEY (event_id, start_time)
);
SELECT create_hypertable('audit_events', 'start_time', if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS idx_audit_tenant_ts ON audit_events (tenant, ts DESC);
CREATE INDEX IF NOT EXISTS idx_audit_type_ts ON audit_events (event_type, ts DESC);

-- ============ 保留策略(§5.2) ============
-- spans 7 天;metrics 原始 14 天;alerts/audit 90 天。rollup 聚合保留 1 年。
SELECT add_retention_policy('spans', INTERVAL '7 days', if_not_exists => TRUE);
SELECT add_retention_policy('metrics_samples', INTERVAL '14 days', if_not_exists => TRUE);
SELECT add_retention_policy('alerts', INTERVAL '90 days', if_not_exists => TRUE);
SELECT add_retention_policy('audit_events', INTERVAL '90 days', if_not_exists => TRUE);

-- ============ WorkflowRun 持久化(C1 §3.4 P1) ============
-- 注:CREATE TABLE IF NOT EXISTS 本身幂等 —— 若已存在但 schema 不匹配(老残留),测试时 DROP 后重 init
-- 顶层 + 子 step 表(状态/输入快照/输出 JSONB/耗时)
CREATE TABLE IF NOT EXISTS workflow_runs (
    run_id           varchar(64)  PRIMARY KEY,
    workflow_name    varchar(256) NOT NULL,
    status           varchar(16)  NOT NULL,        -- RUNNING / COMPLETED / FAILED
    started_at       timestamptz  NOT NULL,
    finished_at      timestamptz,
    start_time       timestamptz  NOT NULL DEFAULT now()  -- hypertable 时间列
);
SELECT create_hypertable('workflow_runs', 'start_time', if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS idx_workflow_runs_name_start ON workflow_runs (workflow_name, start_time DESC);
CREATE INDEX IF NOT EXISTS idx_workflow_runs_status ON workflow_runs (status, start_time DESC);

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
);
CREATE INDEX IF NOT EXISTS idx_workflow_run_steps_status ON workflow_run_steps (status, created_at DESC);

SELECT add_retention_policy('workflow_runs', INTERVAL '90 days', if_not_exists => TRUE);

-- ============ WorkflowDefinition 持久化(C1 §8 扩展) ============
-- 保存可复用 workflow 定义(name PK + YAML/JSON body + 描述 + 时间);普通表
CREATE TABLE IF NOT EXISTS workflow_definitions (
    name          varchar(128) PRIMARY KEY,
    description   varchar(512),
    body          text          NOT NULL,        -- YAML 或 JSON
    format        varchar(8)    NOT NULL DEFAULT 'json',  -- 'json' | 'yaml'
    created_at    timestamptz   NOT NULL DEFAULT now(),
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    varchar(128)
);
CREATE INDEX IF NOT EXISTS idx_workflow_definitions_updated ON workflow_definitions (updated_at DESC);
