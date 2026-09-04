package com.company.agentgateway.domain.cache;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 语义缓存出站端口(Sprint 4 P0):
 * domain 层抽象 L1(精确匹配)+ L2(向量召回)的存储与查询。
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>L1 精确:findByCacheKey(hash(tenant+model+normalizedQuery+tools+temperature))</li>
 *   <li>L2 召回:findSimilarByEmbedding(topK, minSimilarity) — pgvector HNSW ANN</li>
 *   <li>写:upsert(SemanticCacheRecord) — 内部完成 L2 normalize + vec</li>
 *   <li>命中计数:incrementHitCount(recordId) — 异步,不影响读路径</li>
 *   <li>管理:invalidateByTenant(name)、expireOlderThan(cutoff)、统计:stats(tenant)</li>
 * </ul>
 *
 * <h2>实现</h2>
 * <ul>
 *   <li>P0: {@code PgSemanticCacheRepository}(pgvector,HNSW)</li>
 *   <li>二期: Redis(短 L1) + pgvector(长 L2) 双层</li>
 * </ul>
 */
public interface SemanticCachePort {

    /** 精确匹配 L1:按 cacheKey 查单条;命中即返回;未命中返回 empty。 */
    Optional<CacheLookupResult.Candidate> findByCacheKey(String tenantId, String cacheKey);

    /**
     * 向量召回 L2:topK 个候选,按 cosine similarity 降序;只返回 ≥ minSimilarity 的项。
     *
     * @param tenantId        租户隔离(防止跨租户污染)
     * @param model           逻辑模型名(同模型下做召回)
     * @param embedding       查询向量(已 L2 normalized)
     * @param topK            候选数(默认 5)
     * @param minSimilarity   阈值(默认 0.92)
     */
    List<CacheLookupResult.Candidate> findSimilarByEmbedding(
            String tenantId, String model, float[] embedding, int topK, float minSimilarity);

    /**
     * 写入(同 cacheKey 已存在则 update 否则 insert)。
     *
     * @return 新记录的 ID(供后续 hitCount)
     */
    long upsert(SemanticCacheRecord record);

    /** 命中后异步累加 hit_count。失败仅日志,不影响主流程。 */
    void incrementHitCount(long recordId);

    /** 失效:tenant + (可选) name 维度;返回受影响条数。 */
    int invalidateByTenant(String tenantId, String name);

    /** 物理删除过期记录(由 @Scheduled 触发)。 */
    int purgeExpired(Instant cutoff);

    /** 命中率统计(管理仪表盘)。 */
    Stats stats(String tenantId);

    /** 命中率统计 */
    record Stats(long total, long hits, long misses,
                 double hitRatio, double costSavedCents, long tokensSaved) {
        public static Stats empty() {
            return new Stats(0, 0, 0, 0.0, 0.0, 0L);
        }
    }

    /** Top 命中 query(按 hit_count desc) */
    List<TopQuery> topQueries(String tenantId, int limit);

    record TopQuery(long recordId, String cacheKey, String normalizedQuery,
                    long hitCount, double costSavedCents) {}

    /** 持久化形态(语义缓存完整一行)。 */
    record SemanticCacheRecord(
            long id,
            String tenantId,
            String model,
            String cacheKey,
            String normalizedQuery,
            float[] embedding,
            String responseBody,
            Integer tokensIn,
            Integer tokensOut,
            Double costSavedCents,
            Map<String, Object> metadata,
            long hitCount,
            Instant createdAt,
            Instant lastHitAt,
            Instant expiresAt
    ) {
        public SemanticCacheRecord {
            // L2 normalize,确保 HNSW cosine 等价于 dot product
            float norm = 0f;
            for (float v : embedding) norm += v * v;
            norm = (float) Math.sqrt(norm);
            if (norm > 1e-9f) {
                float[] normalized = new float[embedding.length];
                for (int i = 0; i < embedding.length; i++) normalized[i] = embedding[i] / norm;
                embedding = normalized;
            }
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        public static SemanticCacheRecord create(
                String tenantId, String model, String cacheKey, String normalizedQuery,
                float[] embedding, String responseBody, Integer tokensIn, Integer tokensOut,
                Double costSavedCents, Map<String, Object> metadata, Instant expiresAt) {
            return new SemanticCacheRecord(
                    0L, tenantId, model, cacheKey, normalizedQuery, embedding,
                    responseBody, tokensIn, tokensOut, costSavedCents, metadata,
                    0L, Instant.now(), null, expiresAt);
        }
    }
}