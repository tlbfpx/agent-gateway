package com.company.agentgateway.infra.persistence.replay;

import com.company.agentgateway.domain.replay.MetricsQueryPort;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

/**
 * MetricsQueryPort 缓存装饰器(Sprint 2 P5.2):
 * 用 Caffeine 装饰任意 {@link MetricsQueryPort},减少 PG 查询频次。
 *
 * <h2>关键设计</h2>
 * <ul>
 *   <li><b>缓存 Optional.empty()</b>:trace_id 不存在时也缓存,避免反复探测 PG(短 TTL)</li>
 *   <li><b>key = traceId</b>:String 直接做 key;size 上限 10_000(典型 trace 数)</li>
 *   <li><b>TTL = 30s</b>:默认 30 秒过期,平衡新鲜度与 PG 压力</li>
 *   <li><b>穿透保护</b>:底层失败时不让缓存污染(可选 fail-open)</li>
 * </ul>
 *
 * <h2>指标</h2>
 * <ul>
 *   <li><code>metrics_token_cache_hit_total</code></li>
 *   <li><code>metrics_token_cache_miss_total</code></li>
 *   <li><code>metrics_token_cache_size</code>(gauge)</li>
 * </ul>
 */
public class CachedMetricsTokenStore implements MetricsQueryPort {

    private static final Logger log = LoggerFactory.getLogger(CachedMetricsTokenStore.class);

    public static final int DEFAULT_MAX_SIZE = 10_000;
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private final MetricsQueryPort delegate;
    private final Cache<String, Optional<Tokens>> cache;
    private final MeterRegistry meterRegistry;
    private final Counter hitCounter;
    private final Counter missCounter;

    public CachedMetricsTokenStore(MetricsQueryPort delegate) {
        this(delegate, DEFAULT_MAX_SIZE, DEFAULT_TTL, null);
    }

    public CachedMetricsTokenStore(MetricsQueryPort delegate,
                                    int maxSize, Duration ttl, MeterRegistry meterRegistry) {
        if (delegate == null) throw new IllegalArgumentException("delegate required");
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .build();

        if (meterRegistry != null) {
            this.hitCounter = Counter.builder("metrics_token_cache_hit_total").register(meterRegistry);
            this.missCounter = Counter.builder("metrics_token_cache_miss_total").register(meterRegistry);
            io.micrometer.core.instrument.Gauge.builder("metrics_token_cache_size", cache, Cache::estimatedSize)
                    .register(meterRegistry);
        } else {
            this.hitCounter = null;
            this.missCounter = null;
        }
    }

    @Override
    public Optional<Tokens> findTokensForTrace(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return Optional.empty();
        }
        Optional<Tokens> cached = cache.getIfPresent(traceId);
        if (cached != null) {
            if (hitCounter != null) hitCounter.increment();
            return cached;
        }
        if (missCounter != null) missCounter.increment();
        Optional<Tokens> fresh = delegate.findTokensForTrace(traceId);
        // 缓存包括 empty(短 TTL),避免反复探测同一 trace_id
        cache.put(traceId, fresh);
        return fresh;
    }

    /** 清空缓存(管理端点可调用,如强制重读 PG)。 */
    public void invalidateAll() {
        cache.invalidateAll();
        log.info("CachedMetricsTokenStore cache cleared");
    }

    /** 单个 trace 失效。 */
    public void invalidate(String traceId) {
        cache.invalidate(traceId);
    }

    /** 当前缓存条目数(供监控)。 */
    public long estimatedSize() {
        return cache.estimatedSize();
    }
}