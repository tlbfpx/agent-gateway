package com.company.agentgateway.infra.llm.cache;

import com.company.agentgateway.domain.orchestration.PromptCachePort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * PromptCachePort 内存实现（ConcurrentHashMap + 定时清理 + 容量上限按插入序淘汰）。
 *
 * <p>淘汰策略（简化 LRU）： LinkedHashMap 插入序，put 超过 maxEntries 时淘汰最旧条目；
 * 定时任务（ttl/2 或 60s 取小）清理过期条目。读写经 ReentrantLock 串行化（低频操作，锁竞争可忽略）。
 *
 * <p>指标：prompt_cache_hit_total / prompt_cache_miss_total（MeterRegistry 可为 null=无指标环境）。
 */
public class InMemoryPromptCache implements PromptCachePort, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(InMemoryPromptCache.class);

    private final Map<String, CacheEntry> store = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final long ttlMillis;
    private final int maxEntries;
    private final ScheduledExecutorService cleaner;
    private final Counter hitCounter;
    private final Counter missCounter;

    public InMemoryPromptCache(Duration ttl, int maxEntries) {
        this(ttl, maxEntries, null);
    }

    public InMemoryPromptCache(Duration ttl, int maxEntries, MeterRegistry meterRegistry) {
        this.ttlMillis = ttl.toMillis();
        this.maxEntries = maxEntries;
        if (meterRegistry != null) {
            this.hitCounter = Counter.builder("prompt_cache_hit_total")
                    .description("Prompt cache hits").register(meterRegistry);
            this.missCounter = Counter.builder("prompt_cache_miss_total")
                    .description("Prompt cache misses").register(meterRegistry);
        } else {
            this.hitCounter = null;
            this.missCounter = null;
        }
        // 定时清理周期：min(ttl/2, 60s)，至少 1s
        long period = Math.max(1, Math.min(ttlMillis / 2, 60_000));
        this.cleaner = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "prompt-cache-cleaner");
            t.setDaemon(true);
            return t;
        });
        this.cleaner.scheduleAtFixedRate(this::evictExpired, period, period, TimeUnit.MILLISECONDS);
    }

    @Override
    public Optional<CacheEntry> get(String key) {
        lock.lock();
        try {
            CacheEntry entry = store.get(key);
            if (entry == null) {
                miss();
                return Optional.empty();
            }
            if (isExpired(entry)) {
                store.remove(key);
                miss();
                return Optional.empty();
            }
            hit();
            return Optional.of(entry);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(String key, CacheEntry entry) {
        if (key == null || entry == null) return;
        lock.lock();
        try {
            // 容量上限：按插入序淘汰最旧（简化 LRU）
            if (!store.containsKey(key) && store.size() >= maxEntries) {
                Iterator<String> it = store.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            store.put(key, entry);
        } finally {
            lock.unlock();
        }
    }

    /** 当前条目数（含可能未清理的过期条目；测试/观测用）。 */
    public int size() {
        lock.lock();
        try {
            return store.size();
        } finally {
            lock.unlock();
        }
    }

    private boolean isExpired(CacheEntry entry) {
        return System.currentTimeMillis() - entry.createdAt().toEpochMilli() >= ttlMillis;
    }

    private void evictExpired() {
        lock.lock();
        try {
            Instant cutoff = Instant.ofEpochMilli(System.currentTimeMillis() - ttlMillis);
            store.values().removeIf(e -> e.createdAt().isBefore(cutoff));
        } catch (Exception e) {
            log.warn("prompt cache cleanup failed", e);
        } finally {
            lock.unlock();
        }
    }

    private void hit() {
        if (hitCounter != null) hitCounter.increment();
    }

    private void miss() {
        if (missCounter != null) missCounter.increment();
    }

    @Override
    public void close() {
        cleaner.shutdownNow();
    }
}
