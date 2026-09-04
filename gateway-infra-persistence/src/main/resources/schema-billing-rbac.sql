-- ============================================================
-- schema-billing-rbac.sql — D2 计费/配额/预算 + D1 RBAC 持久化
-- 幂等 DDL；由 PgBillingRbacSchemaInitializer 在启动时执行
-- 表设计源自 D2 design §3.2 草案 + D1 Role/RoleBinding 映射
-- ============================================================

-- 计费明细（spec §21.2 usage_record + 单价快照）
CREATE TABLE IF NOT EXISTS billing_records (
    record_id      VARCHAR(64)  PRIMARY KEY,
    tenant_id      VARCHAR(64)  NOT NULL,
    user_id        VARCHAR(64)  NOT NULL,
    model_id       VARCHAR(64)  NOT NULL,
    agent_name     VARCHAR(128) NOT NULL,
    ts             TIMESTAMPTZ  NOT NULL,
    tokens_in      BIGINT       NOT NULL,
    tokens_out     BIGINT       NOT NULL,
    unit_price_in  NUMERIC(18,8) NOT NULL,
    unit_price_out NUMERIC(18,8) NOT NULL,
    cost           NUMERIC(18,6) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_billing_tenant_model_ts
    ON billing_records (tenant_id, model_id, ts);

-- 租户预算（spec §21.4；每租户一条，upsert）
CREATE TABLE IF NOT EXISTS budgets (
    tenant_id            VARCHAR(64) PRIMARY KEY,
    budget_type          VARCHAR(8)  NOT NULL,          -- TOKEN / MONEY
    daily_limit          NUMERIC(18,6) NOT NULL,
    monthly_limit        NUMERIC(18,6) NOT NULL,
    current_daily_used   NUMERIC(18,6) NOT NULL DEFAULT 0,
    current_monthly_used NUMERIC(18,6) NOT NULL DEFAULT 0,
    alert_threshold_pct  INT          NOT NULL,
    alert_sent           BOOLEAN      NOT NULL DEFAULT FALSE,
    suspend_action       VARCHAR(8),                    -- ALERT / THROTTLE / SUSPEND（可空）
    suspend_until        TIMESTAMPTZ,
    over_limit_action    VARCHAR(16)  NOT NULL DEFAULT 'BLOCK',  -- BLOCK / DOWNGRADE（P1 超限降级）
    fallback_model       VARCHAR(128)                   -- DOWNGRADE 时的降级目标模型
);

-- P1 超限降级列（存量库幂等补列）
ALTER TABLE budgets ADD COLUMN IF NOT EXISTS over_limit_action VARCHAR(16) NOT NULL DEFAULT 'BLOCK';
ALTER TABLE budgets ADD COLUMN IF NOT EXISTS fallback_model VARCHAR(128);

-- 配额计数器（spec §16.2；按自然日清零，period 为日键）
CREATE TABLE IF NOT EXISTS quota_counters (
    tenant_id  VARCHAR(64) NOT NULL,
    model_id   VARCHAR(64) NOT NULL,
    dimension  VARCHAR(16) NOT NULL,                    -- REQUEST / MODEL_TOKEN / MONEY
    period     DATE        NOT NULL,
    used_value BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, model_id, dimension, period)
);

-- RBAC 角色（D1；permissions 为 sealed Permission 的 JSON 多态数组）
CREATE TABLE IF NOT EXISTS rbac_roles (
    tenant_id   VARCHAR(64) NOT NULL,
    role_id     VARCHAR(64) NOT NULL,
    name        VARCHAR(64) NOT NULL,
    description VARCHAR(256),
    permissions JSONB       NOT NULL DEFAULT '[]',
    PRIMARY KEY (tenant_id, role_id)
);

-- RBAC 用户-角色绑定（D1）
CREATE TABLE IF NOT EXISTS rbac_role_bindings (
    tenant_id VARCHAR(64) NOT NULL,
    user_id   VARCHAR(64) NOT NULL,
    role_id   VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, user_id, role_id)
);
