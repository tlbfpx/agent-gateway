package com.company.agentgateway.infra.cache;

import com.company.agentgateway.domain.cache.CacheLookupResult;
import com.company.agentgateway.domain.cache.EmbeddingPort;
import com.company.agentgateway.domain.cache.SemanticCachePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SemanticCacheService 单元测试(Sprint 4 P0)。
 * 使用手写 fake port/embedding,避免 Testcontainers 依赖。
 */
class SemanticCacheServiceTest {

    private FakeSemanticCachePort port;
    private FakeEmbedding embedding;
    private SemanticCacheProperties props;
    private SemanticCacheService service;

    @BeforeEach
    void setUp() {
        port = new FakeSemanticCachePort();
        embedding = new FakeEmbedding();
        props = new SemanticCacheProperties();
        props.setEnabled(true);
        props.setSimilarityThreshold(0.92);
        props.setL1MaxSize(100);
        props.setL1Ttl(java.time.Duration.ofMinutes(5));
        props.setAnnTopK(5);
        service = new SemanticCacheService(port, embedding, props);
    }

    @Test
    @DisplayName("disabled 时返回 SKIPPED")
    void disabledReturnsSkipped() {
        props.setEnabled(false);
        var r = service.lookup("tenant", "model", "hello", "sig", 0);
        assertThat(r.kind()).isEqualTo(CacheLookupResult.Kind.SKIPPED);
        assertThat(r.reason()).isEqualTo("disabled");
    }

    @Test
    @DisplayName("含身份证的 query → PII skip")
    void piiSkip() {
        var r = service.lookup("tenant", "model", "我身份证 11010519491231002X", "sig", 0);
        assertThat(r.kind()).isEqualTo(CacheLookupResult.Kind.SKIPPED);
        assertThat(r.reason()).isEqualTo("pii");
        assertThat(embedding.callCount()).isZero();
    }

    @Test
    @DisplayName("空 query 归一化后空 → SKIPPED")
    void emptyAfterNormalize() {
        var r = service.lookup("tenant", "model", "???", "sig", 0);
        assertThat(r.kind()).isEqualTo(CacheLookupResult.Kind.SKIPPED);
        assertThat(r.reason()).isEqualTo("empty_after_normalize");
    }

    @Test
    @DisplayName("L1 命中:同一 cacheKey 第二次命中亚毫秒")
    void l1Hit() {
        // 预先填 L1(用真实 cacheKey 计算)
        String normalized = com.company.agentgateway.domain.cache.QueryNormalizer.normalize("anything");
        String cacheKey = com.company.agentgateway.domain.cache.QueryNormalizer.buildCacheKey(
                "tenant", "model", normalized, "sig", 0);
        var cached = CacheLookupResult.hitExact("cached-response", cacheKey, 1L);
        service.l1().put(cacheKey, cached);

        var r = service.lookup("tenant", "model", "anything", "sig", 0);
        assertThat(r.kind()).isEqualTo(CacheLookupResult.Kind.HIT_EXACT);
        assertThat(r.responseBody()).isEqualTo("cached-response");
        assertThat(embedding.callCount()).isZero(); // 没调 embedding
        assertThat(port.findSimilarCount()).isZero();
    }

    @Test
    @DisplayName("L2 命中:相似度 ≥ threshold → HIT_SIMILAR")
    void l2HitSimilar() {
        embedding.setNextVector(new float[]{0.1f, 0.2f, 0.3f});
        port.addCandidate(42L, "key1", "similar-response", 0.95f);

        var r = service.lookup("tenant", "model", "hello world", "sig", 0);
        assertThat(r.kind()).isEqualTo(CacheLookupResult.Kind.HIT_SIMILAR);
        assertThat(r.similarity()).isEqualTo(0.95f);
        assertThat(r.responseBody()).isEqualTo("similar-response");
        assertThat(embedding.callCount()).isEqualTo(1);
        assertThat(port.findSimilarCount()).isEqualTo(1);
        assertThat(port.incrementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("L2 未命中:port 返回空 → MISS")
    void l2Miss() {
        embedding.setNextVector(new float[]{0.1f, 0.2f, 0.3f});
        // port 无候选
        var r = service.lookup("tenant", "model", "hello", "sig", 0);
        assertThat(r.kind()).isEqualTo(CacheLookupResult.Kind.MISS);
    }

    @Test
    @DisplayName("threshold 边界:0.93 < threshold=0.92 ? 实际 0.91 < 0.92 → MISS")
    void thresholdBoundary() {
        embedding.setNextVector(new float[]{0.1f, 0.2f, 0.3f});
        port.addCandidate(1L, "key", "resp", 0.91f);
        var r = service.lookup("tenant", "model", "hello", "sig", 0);
        assertThat(r.kind()).isEqualTo(CacheLookupResult.Kind.MISS);
    }

    @Test
    @DisplayName("writeAsync:长度不够 → 不写")
    void writeSkippedShort() {
        service.writeAsync("t", "m", "hello", "no", 1, 1, 0.001, "sig", 0);
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        assertThat(port.upsertCount()).isZero();
    }

    @Test
    @DisplayName("writeAsync:正常长度 + token → 写入且回填 L1")
    void writeAndBackfillL1() {
        String resp = "a".repeat(100);
        service.writeAsync("t", "m", "hello world", resp, 10, 20, 0.05, "sig", 0);
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        assertThat(port.upsertCount()).isEqualTo(1);
        // L1 应被回填
        var r = service.lookup("t", "m", "hello world", "sig", 0);
        assertThat(r.kind()).isEqualTo(CacheLookupResult.Kind.HIT_EXACT);
        assertThat(r.responseBody()).isEqualTo(resp);
    }

    @Test
    @DisplayName("writeAsync:tokensOut 不足 → 不写")
    void writeSkippedLowTokens() {
        props.setMinResponseTokens(10);
        service.writeAsync("t", "m", "hello world",
                "a".repeat(100), 5, 5, 0.01, "sig", 0);
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        assertThat(port.upsertCount()).isZero();
    }

    @Test
    @DisplayName("Single-flight:50 个并发同 cacheKey,只调一次 L2")
    void singleFlight() throws Exception {
        embedding.setNextVector(new float[]{0.1f, 0.2f, 0.3f});
        port.addCandidate(1L, "key", "resp", 0.95f);
        // L2 lookup 模拟慢
        port.setL2LatencyMs(100);

        int N = 50;
        ExecutorService pool = Executors.newFixedThreadPool(N);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(N);
        List<CacheLookupResult> results = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    CacheLookupResult r = service.lookup("t", "m", "same query", "sig", 0);
                    synchronized (results) { results.add(r); }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(results).hasSize(N);
        // 所有调用都应命中
        assertThat(results).allMatch(CacheLookupResult::isHit);
        // L2 只调了一次(single-flight)
        assertThat(port.findSimilarCount()).isEqualTo(1);
        assertThat(embedding.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("PiiRefusedException → SKIPPED")
    void piiFromEmbedding() {
        embedding.setThrowPii(true);
        var r = service.lookup("t", "m", "hello world", "sig", 0);
        // service 捕获 PiiRefusedException → 返回 SKIPPED("pii")
        assertThat(r.kind()).isEqualTo(CacheLookupResult.Kind.SKIPPED);
        assertThat(r.reason()).isEqualTo("pii");
    }

    // ─── Fakes ───

    static class FakeSemanticCachePort implements SemanticCachePort {
        private final List<CacheLookupResult.Candidate> candidates = new ArrayList<>();
        private final AtomicInteger findSimilar = new AtomicInteger();
        private final AtomicInteger upsertCount = new AtomicInteger();
        private final AtomicInteger incCount = new AtomicInteger();
        private long l2LatencyMs = 0;

        void addCandidate(long id, String cacheKey, String responseBody, float sim) {
            candidates.add(new CacheLookupResult.Candidate(id, cacheKey, responseBody, sim, List.of()));
        }

        void setL2LatencyMs(long ms) { l2LatencyMs = ms; }

        @Override
        public Optional<CacheLookupResult.Candidate> findByCacheKey(String tenantId, String cacheKey) {
            return Optional.empty();
        }

        @Override
        public List<CacheLookupResult.Candidate> findSimilarByEmbedding(
                String tenantId, String model, float[] embedding, int topK, float minSimilarity) {
            findSimilar.incrementAndGet();
            if (l2LatencyMs > 0) {
                try { Thread.sleep(l2LatencyMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return candidates.stream()
                    .filter(c -> c.similarity() >= minSimilarity)
                    .limit(topK)
                    .toList();
        }

        @Override
        public long upsert(SemanticCacheRecord record) {
            upsertCount.incrementAndGet();
            return upsertCount.get();
        }

        @Override
        public void incrementHitCount(long recordId) {
            incCount.incrementAndGet();
        }

        @Override
        public int invalidateByTenant(String tenantId, String name) { return 0; }

        @Override
        public int purgeExpired(Instant cutoff) { return 0; }

        @Override
        public Stats stats(String tenantId) { return Stats.empty(); }

        @Override
        public List<TopQuery> topQueries(String tenantId, int limit) { return List.of(); }

        int findSimilarCount() { return findSimilar.get(); }
        int upsertCount() { return upsertCount.get(); }
        int incrementCount() { return incCount.get(); }
    }

    static class FakeEmbedding implements EmbeddingPort {
        private final AtomicInteger calls = new AtomicInteger();
        private float[] next = new float[]{0.1f, 0.2f, 0.3f};
        private boolean throwPii = false;

        void setNextVector(float[] v) { next = v; }
        void setThrowPii(boolean b) { throwPii = b; }
        int callCount() { return calls.get(); }

        @Override
        public float[] embed(String text) {
            calls.incrementAndGet();
            if (throwPii) throw new PiiRefusedException("test");
            return next.clone();
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            return texts.stream().map(t -> embed(t)).toList();
        }

        @Override
        public String modelName() { return "fake-model"; }

        @Override
        public int dimensions() { return 3; }
    }
}