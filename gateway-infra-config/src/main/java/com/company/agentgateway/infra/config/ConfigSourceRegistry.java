package com.company.agentgateway.infra.config;

import com.company.agentgateway.domain.config.ConfigChanged;
import com.company.agentgateway.domain.config.ConfigReloadBus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 配置源注册表(Sprint 1 P0 §3.8):汇总各 watcher / subscriber 的状态,
 * 为 UI「Config Reloader」总览页提供数据。
 *
 * <p>状态值:
 * <ul>
 *   <li>{@code synced} — 最近一次发布事件成功</li>
 *   <li>{@code reloading} — 当前在 reload(由 store.setReloading() 进入)</li>
 *   <li>{@code failed} — 最近一次解析或写入失败</li>
 *   <li>{@code unknown} — 从未有过事件(冷启动)</li>
 * </ul>
 *
 * <p>各 store 在 reload() 前后调用 {@link #markReloading(String)} / {@link #markSynced(String)},
 * 失败时调用 {@link #markFailed(String, String)}。
 */
public class ConfigSourceRegistry {

    public enum State { SYNCED, RELOADING, FAILED, UNKNOWN }

    public record Status(String name, State state, String lastError,
                         long lastSuccessEpochMs, long lastFailEpochMs) {}

    private final ConfigReloadBus bus;
    private final Map<String, Status> statuses = new LinkedHashMap<>();
    private final List<String> recentEvents = new CopyOnWriteArrayList<>();

    public ConfigSourceRegistry(ConfigReloadBus bus) {
        this.bus = bus;
        // 默认初始化:常见配置名预设 unknown 状态
        for (String n : List.of("models", "api-keys", "webhooks", "rbac", "mcp-servers", "rate-limit")) {
            statuses.put(n, new Status(n, State.UNKNOWN, null, 0L, 0L));
        }
        // 监听 reload 结果:store 应在 reload 前后调用 markSynced / markFailed
        // 注:store 直接通过 bean 引用调用本类,bus 仅用于日志/诊断
        bus.subscribe("*", ev -> {
            String key = "evt:" + ev.name() + ":" + ev.version();
            recentEvents.add(key);
            if (recentEvents.size() > 100) {
                recentEvents.remove(0);
            }
        });
    }

    public synchronized void markReloading(String name) {
        Status cur = statuses.getOrDefault(name, defaultStatus(name));
        statuses.put(name, new Status(name, State.RELOADING, null, cur.lastSuccessEpochMs(), cur.lastFailEpochMs()));
    }

    public synchronized void markSynced(String name) {
        long now = System.currentTimeMillis();
        Status cur = statuses.getOrDefault(name, defaultStatus(name));
        statuses.put(name, new Status(name, State.SYNCED, null, now, cur.lastFailEpochMs()));
    }

    public synchronized void markFailed(String name, String error) {
        long now = System.currentTimeMillis();
        Status cur = statuses.getOrDefault(name, defaultStatus(name));
        statuses.put(name, new Status(name, State.FAILED, error, cur.lastSuccessEpochMs(), now));
    }

    public synchronized Status statusOf(String name) {
        return statuses.getOrDefault(name, defaultStatus(name));
    }

    public synchronized List<Status> allStatuses() {
        return List.copyOf(statuses.values());
    }

    public List<String> recentEvents() {
        return List.copyOf(recentEvents);
    }

    private static Status defaultStatus(String name) {
        return new Status(name, State.UNKNOWN, null, 0L, 0L);
    }

    public ConfigReloadBus bus() {
        return bus;
    }
}