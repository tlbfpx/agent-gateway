package com.company.agentgateway.infra.persistence.cache;

import com.company.agentgateway.domain.cache.CacheLookupResult;
import com.company.agentgateway.domain.cache.SemanticCachePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 语义缓存 pgvector 仓储(Sprint 4 P0):
 * <ul>
 *   <li>L1 精确:SELECT WHERE tenant_id + model + cache_key</li>
 *   <li>L2 ANN:HNSW cosine 距离 top-K</li>
 *   <li>写:INSERT ON CONFLICT(tenant, model, cache_key) DO UPDATE</li>
 *   <li>过期清理:DELETE WHERE expires_at < now()</li>
 * </ul>
 *
 * <p>注意:本类依赖 pgvector + HNSW(已由 SemanticCacheSchemaInitializer 建好)。
 * 当 schema 不存在时(用户未启用),调用方应在装配层禁用 bean。
 */
public class PgSemanticCacheRepository implements SemanticCachePort {

    private static final Logger log = LoggerFactory.getLogger(PgSemanticCacheRepository.class);

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public PgSemanticCacheRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<CacheLookupResult.Candidate> findByCacheKey(String tenantId, String cacheKey) {
        String sql = """
                SELECT id, response_body, hit_count, metadata
                  FROM semantic_cache
                 WHERE tenant_id = ? AND cache_key = ?
                   AND expires_at > now()
                 LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, cacheKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                long id = rs.getLong("id");
                String body = rs.getString("response_body");
                String meta = rs.getString("metadata");
                List<String> metaList = parseMetadataList(meta);
                return Optional.of(new CacheLookupResult.Candidate(
                        id, cacheKey, body, 1.0f, metaList));
            }
        } catch (SQLException e) {
            log.warn("findByCacheKey failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<CacheLookupResult.Candidate> findSimilarByEmbedding(
            String tenantId, String model, float[] embedding, int topK, float minSimilarity) {
        // pgvector 余弦距离:1 - cosine_similarity = distance
        // L2 normalize 后 dot product = cosine similarity
        String sql = """
                SELECT id, cache_key, response_body,
                       1 - (query_embedding <=> ?) AS similarity,
                       metadata
                  FROM semantic_cache
                 WHERE tenant_id = ? AND model = ?
                   AND expires_at > now()
                   AND (1 - (query_embedding <=> ?)) >= ?
                 ORDER BY query_embedding <=> ?
                 LIMIT ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            String vecStr = toVectorString(embedding);
            ps.setString(1, vecStr);
            ps.setString(2, tenantId);
            ps.setString(3, model);
            ps.setString(4, vecStr);
            ps.setFloat(5, minSimilarity);
            ps.setString(6, vecStr);
            ps.setInt(7, topK);
            List<CacheLookupResult.Candidate> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new CacheLookupResult.Candidate(
                            rs.getLong("id"),
                            rs.getString("cache_key"),
                            rs.getString("response_body"),
                            rs.getFloat("similarity"),
                            parseMetadataList(rs.getString("metadata"))));
                }
            }
            return out;
        } catch (SQLException e) {
            log.warn("findSimilarByEmbedding failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public long upsert(SemanticCacheRecord record) {
        String sql = """
                INSERT INTO semantic_cache
                    (tenant_id, model, cache_key, normalized_query, query_embedding,
                     response_body, tokens_in, tokens_out, cost_saved_cents,
                     metadata, hit_count, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (tenant_id, model, cache_key)
                DO UPDATE SET
                    normalized_query = EXCLUDED.normalized_query,
                    query_embedding = EXCLUDED.query_embedding,
                    response_body = EXCLUDED.response_body,
                    tokens_in = EXCLUDED.tokens_in,
                    tokens_out = EXCLUDED.tokens_out,
                    cost_saved_cents = EXCLUDED.cost_saved_cents,
                    metadata = EXCLUDED.metadata,
                    expires_at = EXCLUDED.expires_at
                RETURNING id
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, record.tenantId());
            ps.setString(2, record.model());
            ps.setString(3, record.cacheKey());
            ps.setString(4, record.normalizedQuery());
            ps.setString(5, toVectorString(record.embedding()));
            ps.setString(6, record.responseBody());
            if (record.tokensIn() != null) ps.setInt(7, record.tokensIn()); else ps.setInt(7, 0);
            if (record.tokensOut() != null) ps.setInt(8, record.tokensOut()); else ps.setInt(8, 0);
            if (record.costSavedCents() != null) ps.setDouble(9, record.costSavedCents()); else ps.setDouble(9, 0);
            ps.setString(10, objectMapper.writeValueAsString(record.metadata()));
            ps.setLong(11, record.hitCount());
            ps.setTimestamp(12, Timestamp.from(record.createdAt()));
            ps.setTimestamp(13, Timestamp.from(record.expiresAt()));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
                return -1L;
            }
        } catch (Exception e) {
            log.error("upsert failed: {}", e.getMessage(), e);
            return -1L;
        }
    }

    @Override
    public void incrementHitCount(long recordId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE semantic_cache SET hit_count = hit_count + 1, last_hit_at = now() WHERE id = ?")) {
            ps.setLong(1, recordId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("incrementHitCount failed for {}: {}", recordId, e.getMessage());
        }
    }

    @Override
    public int invalidateByTenant(String tenantId, String name) {
        // name 占位 — 当前 schema 不存 name 字段(Sprint 4 简化为 tenant 维度失效)
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM semantic_cache WHERE tenant_id = ?")) {
            ps.setString(1, tenantId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("invalidateByTenant failed: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public int purgeExpired(Instant cutoff) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM semantic_cache WHERE expires_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("purgeExpired failed: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public Stats stats(String tenantId) {
        String sql = """
                SELECT
                  COUNT(*) AS total,
                  COALESCE(SUM(hit_count), 0) AS hits,
                  COALESCE(SUM(cost_saved_cents), 0) AS saved
                FROM semantic_cache
                WHERE tenant_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long total = rs.getLong("total");
                    long hits = rs.getLong("hits");
                    double saved = rs.getDouble("saved");
                    double ratio = total > 0 ? (double) hits / total : 0;
                    return new Stats(total, hits, Math.max(0, total - hits), ratio, saved, 0L);
                }
            }
            return Stats.empty();
        } catch (SQLException e) {
            log.warn("stats failed: {}", e.getMessage());
            return Stats.empty();
        }
    }

    @Override
    public List<TopQuery> topQueries(String tenantId, int limit) {
        String sql = """
                SELECT id, cache_key, normalized_query, hit_count, cost_saved_cents
                  FROM semantic_cache
                 WHERE tenant_id = ?
                 ORDER BY hit_count DESC
                 LIMIT ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setInt(2, limit);
            List<TopQuery> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TopQuery(
                            rs.getLong("id"),
                            rs.getString("cache_key"),
                            rs.getString("normalized_query"),
                            rs.getLong("hit_count"),
                            rs.getDouble("cost_saved_cents")));
                }
            }
            return out;
        } catch (SQLException e) {
            log.warn("topQueries failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ─── 工具方法 ───

    /** pgvector 文本格式:`[v1,v2,v3,...]`。 */
    static String toVectorString(float[] v) {
        if (v == null || v.length == 0) return "[]";
        StringBuilder sb = new StringBuilder(v.length * 8);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private List<String> parseMetadataList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            Map<String, Object> m = objectMapper.readValue(json, new TypeReference<>() {});
            return List.of(m.toString());
        } catch (Exception e) {
            return List.of();
        }
    }
}