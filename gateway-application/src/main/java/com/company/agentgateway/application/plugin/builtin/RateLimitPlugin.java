package com.company.agentgateway.application.plugin.builtin;

import com.company.agentgateway.domain.plugin.Plugin;
import com.company.agentgateway.domain.plugin.PluginCapability;
import com.company.agentgateway.domain.plugin.PluginDescriptor;
import com.company.agentgateway.domain.plugin.PluginRequest;
import com.company.agentgateway.domain.plugin.PluginResponse;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RateLimitPlugin —— 单 tenant 100 req/s 内存计数 (Round 15 §wasm-plugins)。
 *
 * <p>官方样本插件 #4,演示 RATE_LIMIT 能力。
 * P0 内存 sliding window(每 1s 桶);R15+2 接 Redis。
 */
public class RateLimitPlugin implements Plugin {

    public static final String ID = "builtin-rate-limit";
    private static final long LIMIT_PER_SECOND = 100;

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(
                ID, "Tenant Rate Limit", "1.0.0",
                "单 tenant 100 req/s 限速(超限返 429)",
                PluginDescriptor.PluginFormat.JAVA,
                Set.of(PluginCapability.RATE_LIMIT),
                List.of("ratelimit", "builtin"),
                true);
    }

    @Override
    public PluginResponse handle(PluginRequest request) {
        long now = System.currentTimeMillis() / 1000;
        String tenant = request.tenant();
        Bucket b = buckets.computeIfAbsent(tenant, k -> new Bucket(now));
        synchronized (b) {
            if (b.second != now) {
                b.second = now;
                b.count.set(0);
            }
            long c = b.count.incrementAndGet();
            if (c > LIMIT_PER_SECOND) {
                return PluginResponse.blocked("rate_limit_exceeded for tenant=" + tenant);
            }
        }
        return new PluginResponse(200, request.headers(), request.body(), false, null);
    }

    private static class Bucket {
        volatile long second;
        final AtomicLong count = new AtomicLong();
        Bucket(long now) { this.second = now; }
    }
}