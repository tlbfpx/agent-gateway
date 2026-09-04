package com.company.agentgateway.domain.config;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 进程内 pub-sub 版 ConfigReloadBus(Sprint 1 P0 默认实现)。
 *
 * <p>特性:
 * <ul>
 *   <li>订阅按 name 分桶,派发时仅通知同名订阅者 + 通配符 "*" 订阅者</li>
 *   <li>派发异步(virtual thread),避免慢订阅者阻塞发布路径</li>
 *   <li>异常隔离:单订阅者抛错仅回调 errorListener,不影响其他订阅者与发布者</li>
 *   <li>维护每个 name 的最近事件,新订阅者可立即同步拿到</li>
 * </ul>
 *
 * <p>domain 层不依赖 slf4j:错误回调通过 {@link #setErrorListener(Consumer)} 由 infra 层注入,
 * 便于测试 + 解耦。
 */
public class InMemoryConfigReloadBus implements ConfigReloadBus {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Sub>> subsByName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConfigChanged> lastByName = new ConcurrentHashMap<>();
    private final AtomicLong subCounter = new AtomicLong();
    private volatile Consumer<Throwable> errorListener = ex -> {/* noop by default */};

    public void setErrorListener(Consumer<Throwable> errorListener) {
        this.errorListener = errorListener == null ? ex -> {} : errorListener;
    }

    @Override
    public void publish(ConfigChanged event) {
        lastByName.put(event.name(), event);
        // 异步派发,避免订阅者耗时阻塞发布者(file watcher 热路径友好)
        Thread.startVirtualThread(() -> dispatch(event.name(), event));
        // 通配符订阅者也通知
        if (!"*".equals(event.name())) {
            Thread.startVirtualThread(() -> dispatch("*", event));
        }
    }

    private void dispatch(String name, ConfigChanged event) {
        List<Sub> list = subsByName.get(name);
        if (list == null || list.isEmpty()) return;
        for (Sub sub : list) {
            try {
                sub.handler().accept(event);
            } catch (RuntimeException ex) {
                try {
                    errorListener.accept(ex);
                } catch (RuntimeException ignore) {
                    // 错误回调自身抛错也不能影响其他订阅者
                }
            }
        }
    }

    @Override
    public Subscription subscribe(String name, Consumer<ConfigChanged> handler) {
        Sub sub = new Sub(name, handler, subCounter.incrementAndGet());
        subsByName.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>()).add(sub);
        return sub;
    }

    @Override
    public void unsubscribe(Subscription subscription) {
        if (!(subscription instanceof Sub s)) return;
        List<Sub> list = subsByName.get(s.name());
        if (list != null) list.remove(s);
    }

    @Override
    public ConfigChanged lastEvent(String name) {
        return lastByName.get(name);
    }

    /** 调试/健康检查用:当前所有订阅者数量。 */
    public Map<String, Integer> subscriberCounts() {
        Map<String, Integer> out = new java.util.HashMap<>();
        subsByName.forEach((k, v) -> out.put(k, v.size()));
        return out;
    }

    private record Sub(String name, Consumer<ConfigChanged> handler, long id) implements Subscription {
        @Override public void cancel() {
            // 由调用方 unsubscribe 完成
        }
    }
}