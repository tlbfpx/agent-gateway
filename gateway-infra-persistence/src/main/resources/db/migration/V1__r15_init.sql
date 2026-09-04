-- Round 15 #2 Pg 持久化 schema (spec 2026-09-02 §pg-persistence §3)
-- Flyway migration: V1
-- 适用 PostgreSQL 14+ / H2 (PostgreSQL mode)

-- ============= Feedback 标注 =============
CREATE TABLE IF NOT EXISTS feedback (
    id           BIGSERIAL PRIMARY KEY,
    trace_id     VARCHAR(255) NOT NULL,
    span_id      VARCHAR(255),
    tenant_id    VARCHAR(64)  NOT NULL,
    user_id      VARCHAR(255),
    model        VARCHAR(128),
    sentiment    VARCHAR(16)  NOT NULL,
    score        SMALLINT,
    comment      TEXT,
    tags         TEXT,
    metadata     TEXT,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_feedback_tenant     ON feedback (tenant_id);
CREATE INDEX IF NOT EXISTS idx_feedback_trace      ON feedback (trace_id);
CREATE INDEX IF NOT EXISTS idx_feedback_sentiment  ON feedback (sentiment);
CREATE INDEX IF NOT EXISTS idx_feedback_created    ON feedback (created_at);

-- ============= AdminUser =============
CREATE TABLE IF NOT EXISTS admin_user (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    name          VARCHAR(255) NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    tenant_id     VARCHAR(64)  NOT NULL,
    api_key_hash  TEXT,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_admin_user_email ON admin_user (tenant_id, email);

-- ============= Team =============
CREATE TABLE IF NOT EXISTS team (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    tenant_id  VARCHAR(64)  NOT NULL,
    owner_id   BIGINT       NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_team_name ON team (tenant_id, name);

-- ============= Team Member =============
CREATE TABLE IF NOT EXISTS team_member (
    team_id BIGINT NOT NULL,
    admin_id BIGINT NOT NULL,
    PRIMARY KEY (team_id, admin_id)
);

-- ============= Prompt Template =============
CREATE TABLE IF NOT EXISTS prompt_template (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    owner_id    BIGINT       NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL,
    tags        TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_prompt_template_name ON prompt_template (tenant_id, name);

-- ============= Prompt Version =============
CREATE TABLE IF NOT EXISTS prompt_version (
    id            BIGSERIAL PRIMARY KEY,
    template_id   BIGINT       NOT NULL,
    version       INT          NOT NULL,
    system_prompt TEXT,
    user_prompt   TEXT         NOT NULL,
    model         VARCHAR(128),
    params        TEXT,
    author_id     BIGINT       NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (template_id, version)
);
