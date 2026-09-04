package com.company.agentgateway.infra.config;

import com.company.agentgateway.domain.config.ConfigChanged;
import com.company.agentgateway.domain.config.ConfigReloadBus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Webhook 订阅持久化(Sprint 1 P0 §3.4):把 WebhookDispatcher 的内存订阅落
 * {@code data/webhooks.json},并在文件被外部修改时通过 {@link ConfigReloadBus} 触发热重载。
 *
 * <h2>契约</h2>
 * <ul>
 *   <li><b>持久化</b>:每次 {@link #upsert}/{@link #remove} 都原子写盘(tmp + move)</li>
 *   <li><b>热重载</b>:启动时订阅 {@code ConfigReloadBus.subscribe("webhooks", ...)};
 *       收到事件后从文件重新读取,推送给外部 listener(WebhookDispatcher 刷新内存)</li>
 *   <li><b>写入幂等</b>:同 url 多次 upsert 等价于 update</li>
 *   <li><b>不抛异常阻塞</b>:文件 IO 失败仅日志告警,内存状态不受影响</li>
 * </ul>
 *
 * <p>数据结构:与 WebhookDispatcher.Subscription 一致 — `{url, secret, events:[...]}`。
 */
public class JsonFileWebhookStore {

    private static final Logger log = LoggerFactory.getLogger(JsonFileWebhookStore.class);
    private static final TypeReference<List<Subscription>> LIST_TYPE = new TypeReference<>() {};

    private final Path file;
    private final ObjectMapper mapper;
    private final ConfigReloadBus bus;
    private final AtomicReference<List<Subscription>> snapshot = new AtomicReference<>(List.of());
    private final CopyOnWriteArrayList<Consumer<List<Subscription>>> changeListeners = new CopyOnWriteArrayList<>();
    private ConfigReloadBus.Subscription reloadSub;

    public JsonFileWebhookStore(Path file, ObjectMapper mapper, ConfigReloadBus bus) {
        this.file = file;
        this.mapper = mapper;
        this.bus = bus;
    }

    /** 启动:加载文件 + 订阅热重载。幂等。 */
    public void start() {
        load();
        if (reloadSub == null && bus != null) {
            reloadSub = bus.subscribe("webhooks", event -> reload());
        }
        log.info("JsonFileWebhookStore started: file={}", file);
    }

    /** 关闭:取消订阅。 */
    public void stop() {
        if (reloadSub != null) {
            bus.unsubscribe(reloadSub);
            reloadSub = null;
        }
    }

    /** 当前订阅快照(只读)。 */
    public List<Subscription> list() {
        return snapshot.get();
    }

    /**
     * 新增或更新订阅(按 url 唯一)。
     *
     * @return 更新后的全量订阅列表
     */
    public synchronized List<Subscription> upsert(Subscription s) {
        List<Subscription> current = new java.util.ArrayList<>(snapshot.get());
        current.removeIf(x -> x.url().equals(s.url()));
        current.add(s);
        return commit(current);
    }

    /** 移除订阅;返回是否实际移除。 */
    public synchronized boolean remove(String url) {
        List<Subscription> current = new java.util.ArrayList<>(snapshot.get());
        boolean removed = current.removeIf(x -> x.url().equals(url));
        if (removed) {
            commit(current);
        }
        return removed;
    }

    /** 注册订阅变更监听(WebhookDispatcher 用以同步内存列表)。 */
    public void addChangeListener(Consumer<List<Subscription>> listener) {
        changeListeners.add(listener);
    }

    /** 文件被外部修改时,重新加载并通知监听者。 */
    public void reload() {
        load();
        for (Consumer<List<Subscription>> l : changeListeners) {
            try {
                l.accept(snapshot.get());
            } catch (RuntimeException ex) {
                log.warn("webhook store change listener failed: {}", ex.getMessage());
            }
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            snapshot.set(List.of());
            log.info("webhooks file {} not found, starting with empty list", file);
            return;
        }
        try {
            List<Subscription> loaded = mapper.readValue(file.toFile(), LIST_TYPE);
            snapshot.set(List.copyOf(loaded));
            log.info("loaded {} webhook subscriptions from {}", loaded.size(), file);
        } catch (IOException e) {
            log.error("failed to load webhooks from {}: {} (keeping previous snapshot)", file, e.getMessage());
        }
    }

    private List<Subscription> commit(List<Subscription> newList) {
        List<Subscription> frozen = List.copyOf(newList);
        snapshot.set(frozen);
        persist(frozen);
        // 同步通知监听者(WebhookDispatcher)
        for (Consumer<List<Subscription>> l : changeListeners) {
            try {
                l.accept(frozen);
            } catch (RuntimeException ex) {
                log.warn("webhook store change listener failed: {}", ex.getMessage());
            }
        }
        return frozen;
    }

    private void persist(List<Subscription> subs) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), subs);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("failed to persist webhooks to {}: {}", file, e.getMessage());
        }
    }

    /**
     * Webhook 订阅 DTO(JsonFileWebhookStore 私有 JSON 序列化形态)。
     *
     * <p>字段名刻意与 WebhookDispatcher.Subscription 对齐:url / secret / events,
     * 便于两端共享序列化。相互转换在 WebhookDispatcher 中完成,避免反向依赖。
     */
    public record Subscription(String url, String secret, List<String> events) {
        public Subscription {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }
}