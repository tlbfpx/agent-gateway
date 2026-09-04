-- 语义缓存 schema(Sprint 4 P0)
-- 由 PgSchemaInitializer 在启动时幂等执行(存在性检查,不重复建)。
-- 依赖 pgvector 扩展(2.4.x+ 支持 HNSW)。

CREATE EXTENSION IF NOT EXISTS vector;

-- ============ semantic_cache(语义缓存,默认保留 30 天) ============
CREATE TABLE IF NOT EXISTS semantic_cache (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    model VARCHAR(64)  NOT NULL,
    cache_key         VARCHAR(64)  NOT NULL,  -- 32-bit hash 十六进制
    normalized_query  TEXT         NOT NULL,
    query_embedding   VECTOR(1536) NOT NULL,  -- text-embedding-3-small 维度
    response_body     TEXT         NOT NULL,
    tokens_in         INT          NOT NULL DEFAULT 0,
    tokens_out        INT          NOT NULL DEFAULT 0,
    cost_saved_cents  DOUBLE PRECISION NOT NULL DEFAULT 0,
    metadata          JSONB        NOT NULL DEFAULT '{}',
    hit_count         BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_hit_at       TIMESTAMPTZ,
    expires_at        TIMESTAMPTZ  NOT NULL
);

-- L1 精确匹配(主路径)
CREATE UNIQUE INDEX IF NOT EXISTS ix_semantic_cache_key
    ON semantic_cache (tenant_id, model, cache_key);

-- L2 ANN 召回(HNSW,cosine 距离;vector 已 normalize 后等价于 dot product)
CREATE INDEX IF NOT EXISTS ix_semantic_cache_embedding_hnsw
    ON semantic_cache USING hnsw (query_embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- 租户+模型 过滤维度(L2 查询 WHERE 子句常用)
CREATE INDEX IF NOT EXISTS ix_semantic_cache_tenant_model
    ON semantic_cache (tenant_id, model, expires_at DESC);

-- Top-N 命中排序(hit_count desc)
CREATE INDEX IF NOT EXISTS ix_semantic_cache_top
    ON semantic_cache (tenant_id, model, hit_count DESC);

-- 自动清理过期记录(由应用层 purgeExpired 调用,此处仅记录 retention 注释)
-- 默认 30 天;Tenant 级 threshold 可改(扩字段:retention_days)
COMMENT ON TABLE semantic_cache IS '语义缓存表(Sprint 4 P0);retention 默认 30 天,由 purgeExpired Job 物理清理';