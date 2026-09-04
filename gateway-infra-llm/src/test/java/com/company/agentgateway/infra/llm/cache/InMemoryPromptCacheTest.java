package com.company.agentgateway.infra.llm.cache;

import com.company.agentgateway.domain.orchestration.PromptCachePort.CacheEntry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** InMemoryPromptCache：命中/未命中/TTL 过期/容量淘汰/指标。 */
class InMemoryPromptCacheTest {

    private CacheEntry entry(String answer) {
        return new CacheEntry(answer, Instant.now(), "qwen");
    }

    @Test
    void 未命中后写入再命中() {
        try (InMemoryPromptCache cache = new InMemoryPromptCache(Duration.ofMinutes(10), 100)) {
            assertThat(cache.get("k1")).isEmpty();
            cache.put("k1", entry("answer-1"));
            var hit = cache.get("k1");
            assertThat(hit).isPresent();
            assertThat(hit.get().answer()).isEqualTo("answer-1");
            assertThat(hit.get().model()).isEqualTo("qwen");
        }
    }

    @Test
    void TTL过期后返回未命中() {
        try (InMemoryPromptCache cache = new InMemoryPromptCache(Duration.ofMillis(80), 100)) {
            cache.put("k1", entry("old"));
            assertThat(cache.get("k1")).isPresent();
            await().atMost(Duration.ofSeconds(2)).until(() -> cache.get("k1").isEmpty());
        }
    }

    @Test
    void 超出容量按插入序淘汰最旧() {
        try (InMemoryPromptCache cache = new InMemoryPromptCache(Duration.ofMinutes(10), 2)) {
            cache.put("k1", entry("a1"));
            cache.put("k2", entry("a2"));
            cache.put("k3", entry("a3")); // k1 被淘汰
            assertThat(cache.get("k1")).isEmpty();
            assertThat(cache.get("k2")).isPresent();
            assertThat(cache.get("k3")).isPresent();
            assertThat(cache.size()).isEqualTo(2);
        }
    }

    @Test
    void 命中未命中写入Micrometer计数器() {
        var registry = new SimpleMeterRegistry();
        try (InMemoryPromptCache cache = new InMemoryPromptCache(Duration.ofMinutes(10), 100, registry)) {
            cache.get("miss");                       // miss
            cache.put("k1", entry("a"));
            cache.get("k1");                         // hit
            cache.get("k2");                         // miss
            assertThat(registry.get("prompt_cache_hit_total").counter().count()).isEqualTo(1.0);
            assertThat(registry.get("prompt_cache_miss_total").counter().count()).isEqualTo(2.0);
        }
    }
}
