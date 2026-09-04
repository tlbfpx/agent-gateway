package com.company.agentgateway.infra.persistence.replay;

import com.company.agentgateway.domain.replay.MetricsQueryPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CachedMetricsTokenStore 测试(Sprint 2 P5.2):
 * 装饰器 + Caffeine 缓存;验证缓存命中/过期/穿透 + 指标。
 */
class CachedMetricsTokenStoreTest {

    private CountingDelegate delegate;
    private SimpleMeterRegistry meterRegistry;
    private CachedMetricsTokenStore cache;

    @BeforeEach
    void setUp() {
        delegate = new CountingDelegate();
        meterRegistry = new SimpleMeterRegistry();
        cache = new CachedMetricsTokenStore(delegate, 100, java.time.Duration.ofSeconds(30), meterRegistry);
    }

    @Test
    @DisplayName("第一次 miss → 第二次 hit,delegate 只调用 1 次")
    void cacheHitOnSecondCall() {
        delegate.script = Map.of("llm_tokens_in", 100.0);

        Optional<MetricsQueryPort.Tokens> r1 = cache.findTokensForTrace("t1");
        assertThat(r1).isPresent();
        assertThat(delegate.callCount.get()).isEqualTo(1);

        Optional<MetricsQueryPort.Tokens> r2 = cache.findTokensForTrace("t1");
        assertThat(r2).isPresent();
        assertThat(delegate.callCount.get()).as("第二次走缓存").isEqualTo(1);
    }

    @Test
    @DisplayName("缓存空 Optional,避免反复探测")
    void cachesEmptyResult() {
        delegate.script = Map.of();  // 空结果

        assertThat(cache.findTokensForTrace("missing-trace")).isEmpty();
        assertThat(cache.findTokensForTrace("missing-trace")).isEmpty();
        assertThat(delegate.callCount.get()).as("空结果也缓存").isEqualTo(1);
    }

    @Test
    @DisplayName("invalidate 后下次调用重新查")
    void invalidateForcesReload() {
        delegate.script = Map.of("llm_tokens_in", 100.0);
        cache.findTokensForTrace("t1");
        assertThat(delegate.callCount.get()).isEqualTo(1);

        cache.invalidate("t1");
        cache.findTokensForTrace("t1");
        assertThat(delegate.callCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("invalidateAll 清空全部缓存")
    void invalidateAllClearsEverything() {
        delegate.script = Map.of("llm_tokens_in", 100.0);
        cache.findTokensForTrace("t1");
        cache.findTokensForTrace("t2");
        cache.findTokensForTrace("t3");
        assertThat(delegate.callCount.get()).isEqualTo(3);

        cache.invalidateAll();
        cache.findTokensForTrace("t1");
        assertThat(delegate.callCount.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("Prometheus 指标:hit / miss / size")
    void metricsRegistered() {
        delegate.script = Map.of("llm_tokens_in", 100.0);
        cache.findTokensForTrace("t1");  // miss
        cache.findTokensForTrace("t1");  // hit
        cache.findTokensForTrace("t2");  // miss

        assertThat(meterRegistry.find("metrics_token_cache_hit_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("metrics_token_cache_miss_total").counter().count()).isEqualTo(2.0);
        assertThat(meterRegistry.find("metrics_token_cache_size").gauge().value()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("TTL 过期后重新查 PG")
    void ttlExpiry() throws Exception {
        // 短 TTL 用于测试
        var shortCache = new CachedMetricsTokenStore(delegate, 10, java.time.Duration.ofMillis(50), meterRegistry);
        delegate.script = Map.of("llm_tokens_in", 100.0);

        shortCache.findTokensForTrace("t1");
        assertThat(delegate.callCount.get()).isEqualTo(1);

        Thread.sleep(80);  // 等待 TTL 过期

        shortCache.findTokensForTrace("t1");
        assertThat(delegate.callCount.get()).as("TTL 过期后第二次调用 PG").isEqualTo(2);
    }

    @Test
    @DisplayName("estimatedSize:非负数")
    void estimatedSizeBasic() {
        assertThat(cache.estimatedSize()).isGreaterThanOrEqualTo(0);
        delegate.script = Map.of("llm_tokens_in", 1.0);
        cache.findTokensForTrace("t1");
        assertThat(cache.estimatedSize()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("null/空 traceId 短路(不查缓存/不查 PG)")
    void nullTraceIdShortCircuit() {
        assertThat(cache.findTokensForTrace(null)).isEmpty();
        assertThat(cache.findTokensForTrace("")).isEmpty();
        assertThat(delegate.callCount.get()).isZero();
    }

    @Test
    @DisplayName("null delegate 构造 → 抛 IllegalArgumentException")
    void nullDelegateRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new CachedMetricsTokenStore(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── 计数 delegate(用于测试 cache 行为) ───

    static class CountingDelegate implements MetricsQueryPort {
        AtomicInteger callCount = new AtomicInteger();
        Map<String, Double> script = Map.of();

        @Override
        public Optional<Tokens> findTokensForTrace(String traceId) {
            callCount.incrementAndGet();
            if (script.isEmpty()) return Optional.empty();
            double in = script.getOrDefault("llm_tokens_in", 0.0);
            double out = script.getOrDefault("llm_tokens_out", 0.0);
            if (in == 0 && out == 0) return Optional.empty();
            return Optional.of(new Tokens((int) in, (int) out));
        }
    }
}