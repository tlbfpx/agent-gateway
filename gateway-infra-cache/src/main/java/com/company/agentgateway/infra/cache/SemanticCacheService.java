package com.company.agentgateway.infra.cache;

import com.company.agentgateway.domain.cache.CacheLookupResult;
import com.company.agentgateway.domain.cache.EmbeddingPort;
import com.company.agentgateway.domain.cache.PiiDetector;
import com.company.agentgateway.domain.cache.QueryNormalizer;
import com.company.agentgateway.domain.cache.SemanticCacheFacade;
import com.company.agentgateway.domain.cache.SemanticCachePort;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 语义缓存服务(Sprint 4 P0):
 * <ul>
 *   <li>L1 — Caffeine 精确匹配(cacheKey → response,亚毫秒)</li>
 *   <li>L2 — pgvector ANN 召回(5-15ms)</li>
 *   <li>Single-flight — ConcurrentHashMap dedup,同 cacheKey 并发只一次 L2 lookup</li>
 *   <li>异步写 — 写盘不阻塞 LLM 主路径</li>
 *   <li>PII 拦截 — 含身份证/银行卡 等直接 SKIP(避免落到第三方 embedding)</li>
 * </ul>
 */
public class SemanticCacheService implements SemanticCacheFacade {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    private final SemanticCachePort port;
    private final EmbeddingPort embedding;
    private final SemanticCacheProperties props;
    private final Cache<String, CacheLookupResult> l1;
    private final MeterRegistry meterRegistry;
    /** Single-flight in-flight lookup dedup;key=cacheKey。 */
    private final ConcurrentHashMap<String, CompletableFuture<CacheLookupResult>> inflight = new ConcurrentHashMap<>();

    public SemanticCacheService(SemanticCachePort port, EmbeddingPort embedding,
                               SemanticCacheProperties props, MeterRegistry meterRegistry) {
        this.port = port;
        this.embedding = embedding;
        this.props = props;
        this.meterRegistry = meterRegistry;
        this.l1 = Caffeine.newBuilder()
                .maximumSize(props.getL1MaxSize())
                .expireAfterWrite(props.getL1Ttl().toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }

    /** 兼容旧调用(无 metrics);用于单元测试。 */
    public SemanticCacheService(SemanticCachePort port, EmbeddingPort embedding,
                                SemanticCacheProperties props) {
        this(port, embedding, props, io.micrometer.core.instrument.Metrics.globalRegistry);
    }

    /**
     * 主入口:三段漏斗
     * <ol>
     *   <li>cacheKey 计算 + PII 检测 + L1 精确命中</li>
     *   <li>L1 未命中 → single-flight L2(Embedding + pgvector ANN)</li>
     *   <li>≥ threshold → HIT_SIMILAR</li>
     * </ol>
     */
    public CacheLookupResult lookup(String tenantId, String model, String rawQuery,
                                    String toolsSignature, int temperatureBucket) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return doLookup(tenantId, model, rawQuery, toolsSignature, temperatureBucket);
        } finally {
            sample.stop(meterRegistry.timer("semantic_cache_lookup_duration_seconds",
                    Tags.of("tenant", tenantId, "model", model)));
        }
    }

    private CacheLookupResult doLookup(String tenantId, String model, String rawQuery,
                                        String toolsSignature, int temperatureBucket) {
        if (!props.isEnabled()) {
            recordMiss(tenantId, model, "disabled");
            return CacheLookupResult.skipped("disabled", null, null);
        }
        String normalized = QueryNormalizer.normalize(rawQuery);
        if (normalized.isEmpty()) {
            recordMiss(tenantId, model, "empty");
            return CacheLookupResult.skipped("empty_after_normalize", normalized, null);
        }
        if (PiiDetector.containsPii(rawQuery)) {
            meterRegistry.counter("semantic_cache_pii_skipped_total",
                    Tags.of("tenant", tenantId)).increment();
            return CacheLookupResult.skipped("pii", normalized, null);
        }
        String cacheKey = QueryNormalizer.buildCacheKey(tenantId, model, normalized,
                toolsSignature, temperatureBucket);

        // L1 精确
        CacheLookupResult l1Hit = l1.getIfPresent(cacheKey);
        if (l1Hit != null && l1Hit.isHit()) {
            recordHit(tenantId, model, "exact");
            return l1Hit;
        }

        // L2 single-flight
        CompletableFuture<CacheLookupResult> future = inflight.computeIfAbsent(cacheKey, k -> {
            CompletableFuture<CacheLookupResult> f = new CompletableFuture<>();
            CompletableFuture.runAsync(() -> {
                try {
                    f.complete(doL2Lookup(tenantId, model, normalized, cacheKey));
                } catch (Throwable ex) {
                    f.completeExceptionally(ex);
                } finally {
                    inflight.remove(k);
                }
            });
            return f;
        });

        try {
            CacheLookupResult result = future.get(3, TimeUnit.SECONDS);
            if (result != null && result.isHit()) {
                l1.put(cacheKey, result);
                if (result.recordId() > 0) {
                    CompletableFuture.runAsync(() -> port.incrementHitCount(result.recordId()));
                }
                recordHit(tenantId, model, result.kind() == CacheLookupResult.Kind.HIT_EXACT ? "exact" : "similar");
            } else {
                recordMiss(tenantId, model, result != null ? result.reason() : "no_candidates");
            }
            return result != null ? result : CacheLookupResult.miss(normalized, cacheKey);
        } catch (Exception e) {
            log.debug("L2 lookup error: {}", e.getMessage());
            recordMiss(tenantId, model, "error");
            return CacheLookupResult.miss(normalized, cacheKey);
        }
    }

    private void recordHit(String tenantId, String model, String kind) {
        meterRegistry.counter("semantic_cache_hits_total",
                Tags.of("tenant", tenantId, "model", model, "kind", kind)).increment();
    }

    private void recordMiss(String tenantId, String model, String reason) {
        meterRegistry.counter("semantic_cache_misses_total",
                Tags.of("tenant", tenantId, "model", model, "reason", reason == null ? "unknown" : reason)).increment();
    }

    /** 实际 L2 查询(由 single-flight 包装,只跑一次) */
    private CacheLookupResult doL2Lookup(String tenantId, String model, String normalized, String cacheKey) {
        try {
            float[] vec = embedding.embed(normalized);
            List<CacheLookupResult.Candidate> cands = port.findSimilarByEmbedding(
                    tenantId, model, vec, props.getAnnTopK(), (float) props.getSimilarityThreshold());
            if (cands.isEmpty()) return CacheLookupResult.miss(normalized, cacheKey);
            CacheLookupResult.Candidate top = cands.get(0);
            return CacheLookupResult.hitSimilar(top.responseBody(), top.similarity(),
                    top.cacheKey(), top.recordId());
        } catch (EmbeddingPort.PiiRefusedException e) {
            return CacheLookupResult.skipped("pii", normalized, cacheKey);
        } catch (Exception e) {
            log.warn("L2 lookup failed for tenant={} model={}: {}", tenantId, model, e.getMessage());
            return CacheLookupResult.miss(normalized, cacheKey);
        }
    }

    /**
     * 写缓存(异步,不阻塞调用方)。
     *
     * <p>策略:
     * <ul>
     *   <li>2xx + 长度 ≥ minResponseLength + tokens ≥ minResponseTokens 才写</li>
     *   <li>TTL = props.ttl ± jitter</li>
     *   <li>失败仅日志,不抛(主流程不应被缓存写失败拖死)</li>
     * </ul>
     */
    public void writeAsync(String tenantId, String model, String rawQuery, String responseBody,
                           Integer tokensIn, Integer tokensOut, Double costSavedCents,
                           String toolsSignature, int temperatureBucket) {
        if (!props.isEnabled() || responseBody == null) return;
        if (responseBody.length() < props.getMinResponseLength()) return;
        if (tokensOut != null && tokensOut < props.getMinResponseTokens()) return;

        String normalized = QueryNormalizer.normalize(rawQuery);
        if (normalized.isEmpty() || PiiDetector.containsPii(rawQuery)) return;
        String cacheKey = QueryNormalizer.buildCacheKey(tenantId, model, normalized,
                toolsSignature, temperatureBucket);

        // 写完后回填 L1
        CompletableFuture.runAsync(() -> {
            try {
                float[] embeddingVec = embedding.embed(normalized);
                Instant expiresAt = computeExpiresAt();
                SemanticCachePort.SemanticCacheRecord rec = SemanticCachePort.SemanticCacheRecord.create(
                        tenantId, model, cacheKey, normalized,
                        embeddingVec, responseBody,
                        tokensIn, tokensOut, costSavedCents,
                        Map.of("embeddingModel", embedding.modelName()),
                        expiresAt);
                long id = port.upsert(rec);
                if (id > 0) {
                    log.debug("cache written: id={} tenant={} model={} ttl={}", id, tenantId, model, expiresAt);
                    // 同步回填 L1(命中后立即可走亚毫秒路径)
                    CacheLookupResult l1Entry = CacheLookupResult.hitExact(responseBody, cacheKey, id);
                    l1.put(cacheKey, l1Entry);
                    // 指标:写入 + 估算成本节省
                    meterRegistry.counter("semantic_cache_writes_total",
                            Tags.of("tenant", tenantId, "model", model)).increment();
                    if (costSavedCents != null) {
                        meterRegistry.counter("semantic_cache_cost_saved_cents_total",
                                Tags.of("tenant", tenantId, "model", model)).increment(costSavedCents);
                    }
                }
            } catch (EmbeddingPort.PiiRefusedException e) {
                log.debug("cache write skipped: PII detected");
            } catch (Exception e) {
                log.warn("cache write failed: {}", e.getMessage());
            }
        });
    }

    private Instant computeExpiresAt() {
        long baseMs = props.getTtl().toMillis();
        double jitter = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * props.getJitterRatio();
        long ttl = (long) (baseMs * (1 + jitter));
        return Instant.now().plus(Duration.ofMillis(ttl));
    }

    public SemanticCachePort port() { return port; }
    public EmbeddingPort embedding() { return embedding; }
    public SemanticCacheProperties props() { return props; }
    public Cache<String, CacheLookupResult> l1() { return l1; }
}