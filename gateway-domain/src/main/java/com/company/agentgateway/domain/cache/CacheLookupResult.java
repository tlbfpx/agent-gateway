package com.company.agentgateway.domain.cache;

import java.util.List;

/**
 * 语义缓存查询结果(Sprint 4 P0)。
 *
 * <p>三种状态:
 * <ul>
 *   <li>{@code HIT_EXACT} — L1 hash 命中,延迟亚毫秒</li>
 *   <li>{@code HIT_SIMILAR} — L2 ANN 命中,余弦相似度 ≥ threshold,延迟 5-15ms</li>
 *   <li>{@code MISS} — 未命中,继续走 LLM 路径</li>
 *   <li>{@code SKIPPED} — PII / 禁用 / 错误,跳过缓存</li>
 * </ul>
 *
 * <p>HIT 时附带响应体、相似度、命中记录 ID(供指标/审计使用);
 * MISS 时附带 normalizedQuery 用于上层计算 embedding 后回写。
 */
public record CacheLookupResult(
        Kind kind,
        String responseBody,
        float similarity,
        String cacheKey,
        long recordId,
        String normalizedQuery,
        String reason
) {
    public enum Kind { HIT_EXACT, HIT_SIMILAR, MISS, SKIPPED }

    public boolean isHit() {
        return kind == Kind.HIT_EXACT || kind == Kind.HIT_SIMILAR;
    }

    public static CacheLookupResult miss(String normalizedQuery, String cacheKey) {
        return new CacheLookupResult(Kind.MISS, null, 0f, cacheKey, 0L, normalizedQuery, null);
    }

    public static CacheLookupResult hitExact(String responseBody, String cacheKey, long recordId) {
        return new CacheLookupResult(Kind.HIT_EXACT, responseBody, 1.0f, cacheKey, recordId, null, null);
    }

    public static CacheLookupResult hitSimilar(String responseBody, float similarity,
                                               String cacheKey, long recordId) {
        return new CacheLookupResult(Kind.HIT_SIMILAR, responseBody, similarity, cacheKey, recordId, null, null);
    }

    public static CacheLookupResult skipped(String reason, String normalizedQuery, String cacheKey) {
        return new CacheLookupResult(Kind.SKIPPED, null, 0f, cacheKey, 0L, normalizedQuery, reason);
    }

    /** 命中候选项(L2 ANN 查询返回,供业务侧进一步过滤) */
    public record Candidate(long recordId, String cacheKey, String responseBody,
                            float similarity, List<String> metadata) {}
}