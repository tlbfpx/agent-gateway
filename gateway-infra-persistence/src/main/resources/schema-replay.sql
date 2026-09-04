-- Replay payload 持久化 schema(Sprint 2 P0)
-- 独立于 spans,避免 OTel 属性膨胀。
-- 30 天 retention(可配 7/30/90/365),由 ReplayAutoConfiguration 中 @Scheduled purge。

CREATE TABLE IF NOT EXISTS trace_payloads (
    trace_id      VARCHAR(32)  NOT NULL,
    span_id       VARCHAR(16)  NOT NULL,
    role          VARCHAR(16)  NOT NULL,    -- REQUEST / RESPONSE / TOOL_CALL / TOOL_RESULT
    content_type  VARCHAR(32)  NOT NULL,    -- text | messages_json | tool_call_json | ...
    body_enc      BYTEA        NOT NULL,    -- AES-256-GCM(body, payload_key)
    bytes         INT          NOT NULL,    -- 加密前 size(用于 metrics)
    captured_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (trace_id, span_id, role, captured_at)
);

-- 按 trace 查所有 payload
CREATE INDEX IF NOT EXISTS ix_trace_payloads_trace
    ON trace_payloads (trace_id, captured_at DESC);

-- retention 清理(由应用层 @Scheduled 调用 purgeBefore,此处仅注释)
COMMENT ON TABLE trace_payloads IS 'Replay 原始 payload(Sprint 2 P0);默认保留 30 天;AES-256-GCM 加密';

-- replay_jobs(异步长跑任务)
CREATE TABLE IF NOT EXISTS replay_jobs (
    id              VARCHAR(36)  PRIMARY KEY,    -- UUID
    source_trace_id VARCHAR(32)  NOT NULL,
    replay_trace_id VARCHAR(32),
    status          VARCHAR(16)  NOT NULL,    -- PENDING / RUNNING / COMPLETED / FAILED / CANCELLED
    kind            VARCHAR(16)  NOT NULL,    -- DEFAULT / WHAT_IF / BATCH / LOAD
    safe_replay     BOOLEAN      NOT NULL DEFAULT TRUE,
    overrides_json  TEXT,
    started_at      TIMESTAMPTZ  NOT NULL,
    finished_at     TIMESTAMPTZ,
    actor           VARCHAR(64),
    error_message   TEXT,
    metadata        JSONB        NOT NULL DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS ix_replay_jobs_status
    ON replay_jobs (status, started_at DESC);
CREATE INDEX IF NOT EXISTS ix_replay_jobs_source
    ON replay_jobs (source_trace_id);