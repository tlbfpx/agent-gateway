package com.company.agentgateway.domain.registry;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RoundRobin 实例选择器(spec B §4.3)。
 *
 * <p>游标按 agentName 独立维护(避免不同 Agent 互相影响);
 * 失败计数(短期窗口)用于失败转移时的临时回避 —— 超过阈值后该 url 被跳过,但下一窗口自动恢复(简单可用,实例级熔断留待下轮)。
 */
public class RoundRobinEndpointSelector implements EndpointSelector {

    /** 失败计数窗口(短):同一 url 失败 3 次后本轮跳过,下一轮自动重置 */
    private static final int FAIL_THRESHOLD = 3;

    private final ConcurrentHashMap<String, AtomicLong> cursors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> failures = new ConcurrentHashMap<>();

    @Override
    public String select(AgentCard card) {
        if (card == null) return null;
        java.util.List<String> urls = card.endpointUrls();
        if (urls == null || urls.isEmpty()) return null;

        // 过滤掉本轮被临时回避的 url
        java.util.List<String> available = urls.stream()
                .filter(u -> failure(u) < FAIL_THRESHOLD)
                .toList();
        if (available.isEmpty()) {
            // 全被回避:全部重置,重新挑选(允许重试)
            failures.clear();
            available = urls;
        }

        AtomicLong cursor = cursors.computeIfAbsent(card.name(), k -> new AtomicLong());
        int idx = (int) (cursor.getAndIncrement() & Integer.MAX_VALUE);  // 防溢出
        return available.get(idx % available.size());
    }

    @Override
    public void onFailure(String url) {
        if (url == null) return;
        failures.computeIfAbsent(url, k -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public void onSuccess(String url) {
        if (url == null) return;
        AtomicLong f = failures.get(url);
        if (f != null) f.set(0);
    }

    private long failure(String url) {
        AtomicLong f = failures.get(url);
        return f == null ? 0 : f.get();
    }
}