package com.company.agentgateway.domain.cache;

/**
 * 语义缓存门面接口(Sprint 4 P0):domain 层契约,infra 层实现。
 *
 * <p>对外暴露两个动作:lookup + writeAsync。ChatOrchestrator 仅依赖此接口。
 *
 * <h2>查找漏斗</h2>
 * <ol>
 *   <li>PII 检测 + query 归一化 + cacheKey 计算</li>
 *   <li>L1 精确匹配(Caffeine,亚毫秒)</li>
 *   <li>L2 ANN 召回(pgvector HNSW,5-15ms)</li>
 * </ol>
 *
 * <h2>写策略</h2>
 * 异步批写(线程池 offload);2xx + 长度阈值 + tokens 阈值 才写;TTL ± jitter。
 */
public interface SemanticCacheFacade {

    /**
     * 查缓存。返回结果总是非 null;命中由 result.isHit() 判定。
     */
    CacheLookupResult lookup(String tenantId, String model, String rawQuery,
                             String toolsSignature, int temperatureBucket);

    /**
     * 异步写缓存。不抛异常(主流程不能被缓存写失败拖死)。
     */
    void writeAsync(String tenantId, String model, String rawQuery, String responseBody,
                    Integer tokensIn, Integer tokensOut, Double costSavedCents,
                    String toolsSignature, int temperatureBucket);
}