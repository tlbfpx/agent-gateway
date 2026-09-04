package com.company.agentgateway.infra.config;

import com.company.agentgateway.domain.config.ConfigChanged;
import com.company.agentgateway.domain.config.ConfigReloadBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 配置目录文件监听器(Sprint 1 P0 §3.4):
 * <p>监听 {@code data/} 下的 {@code *.json} 文件,把外部修改(create/modify/delete)翻译为
 * {@link ConfigChanged} 事件发到 {@link ConfigReloadBus}。
 *
 * <h2>设计要点</h2>
 * <ul>
 *   <li><b>NIO WatchService</b>:内核级 inotify/FSEvents,避免轮询</li>
 *   <li><b>防抖 200ms</b>:同文件短时间多次 modify 合并为一次,防止 vi/sed 等编辑器的中间态触发反复 reload</li>
 *   <li><b>SHA-256 checksum</b>:内容相同的事件丢弃,避免同次保存的 ENTRY_MODIFY + ENTRY_CREATE 双触发</li>
 *   <li><b>name 映射</b>:文件名 {@code models.json} → config name {@code models},{@code api-keys.json} → {@code api-keys}</li>
 *   <li><b>自动解析 payload</b>:用 Jackson 解析 JSON 为 {@code List<Map<String,Object>>},订阅者直接用</li>
 *   <li><b>优雅关停</b>:{@link #stop()} 释放 WatchService 与调度线程</li>
 * </ul>
 *
 * <p><b>作用域</b>:仅负责"监听文件变更 → 发总线事件",不直接调 store reload——store 自己订阅总线,
 * 解耦"事件源"(file/nacos/k8s)与"消费方"(各 store)。
 */
public class ConfigFileWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConfigFileWatcher.class);

    /** 防抖窗口(ms):同文件多次 modify 在窗口内合并为一次。 */
    public static final long DEFAULT_DEBOUNCE_MS = 200L;

    /** 调度线程池关闭最大等待时间。 */
    private static final long STOP_TIMEOUT_SECONDS = 5L;

    private final Path dataDir;
    private final ConfigReloadBus bus;
    private final ObjectMapper mapper;
    private final long debounceMs;
    private final boolean parsePayload;

    private WatchService watchService;
    private Thread watcherThread;
    private ScheduledExecutorService scheduler;
    private final Map<Path, PendingEvent> pending = new ConcurrentHashMap<>();
    /** 已知文件最近一次的 SHA-256,用于去重。 */
    private final Map<Path, String> lastChecksums = new ConcurrentHashMap<>();
    /** 文件名(name)→ 已订阅的 key 状态,便于诊断。 */
    private volatile boolean running = false;

    public ConfigFileWatcher(Path dataDir, ConfigReloadBus bus, ObjectMapper mapper) {
        this(dataDir, bus, mapper, DEFAULT_DEBOUNCE_MS, true);
    }

    public ConfigFileWatcher(Path dataDir, ConfigReloadBus bus, ObjectMapper mapper,
                             long debounceMs, boolean parsePayload) {
        this.dataDir = dataDir;
        this.bus = bus;
        this.mapper = mapper;
        this.debounceMs = debounceMs;
        this.parsePayload = parsePayload;
    }

    /** 启动监听。已启动则幂等返回。 */
    public synchronized void start() throws IOException {
        if (running) return;
        if (!Files.isDirectory(dataDir)) {
            throw new IllegalArgumentException("dataDir is not a directory: " + dataDir);
        }
        this.watchService = FileSystems.getDefault().newWatchService();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "config-file-watcher-scheduler");
            t.setDaemon(true);
            return t;
        });
        // 递归注册 dataDir 与其直接子目录(history 子目录)
        registerRecursive(dataDir);

        // 先置 running = true,再启动线程,避免 watchLoop 启动即退出(race)
        running = true;
        this.watcherThread = new Thread(this::watchLoop, "config-file-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
        log.info("ConfigFileWatcher started: dir={}, debounceMs={}, parsePayload={}",
                dataDir, debounceMs, parsePayload);
    }

    private void registerRecursive(Path dir) throws IOException {
        dir.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        // 监听 history 子目录的创建/修改(用于历史快照)
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory).forEach(child -> {
                try {
                    child.register(watchService,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE);
                } catch (IOException e) {
                    log.warn("register child dir failed: {} - {}", child, e.getMessage());
                }
            });
        }
    }

    private void watchLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.nio.file.ClosedWatchServiceException e) {
                // WatchService 已关闭(正常关停),退出循环
                return;
            } catch (RuntimeException e) {
                if (!running) return;
                log.warn("watcher poll error: {}", e.getMessage());
                continue;
            }

            Path watched = (Path) key.watchable();
            for (WatchEvent<?> ev : key.pollEvents()) {
                WatchEvent.Kind<?> kind = ev.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                @SuppressWarnings("unchecked")
                WatchEvent<Path> pathEv = (WatchEvent<Path>) ev;
                Path child = pathEv.context();
                Path full = watched.resolve(child);

                if (!isTracked(full)) continue;

                if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                    // 文件删除:立刻(不需要防抖)发一个不带 payload 的 delete 事件
                    publishDelete(full);
                    lastChecksums.remove(full);
                } else {
                    // CREATE / MODIFY:防抖 + checksum
                    scheduleDebounced(full);
                }
            }

            boolean valid = key.reset();
            if (!valid) {
                log.warn("watch key no longer valid: {}", watched);
            }
        }
    }

    private boolean isTracked(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".json");
    }

    private void scheduleDebounced(Path file) {
        long delay = debounceMs;
        PendingEvent existing = pending.get(file);
        if (existing != null) {
            existing.future.cancel(false);
        }
        var future = scheduler.schedule(() -> {
            pending.remove(file);
            try {
                publishModify(file);
            } catch (Exception e) {
                log.error("publishModify failed for {}: {}", file, e.getMessage(), e);
            }
        }, delay, TimeUnit.MILLISECONDS);
        pending.put(file, new PendingEvent(future));
    }

    private void publishModify(Path file) {
        if (!Files.exists(file)) {
            // modify 事件后文件被删:降级为 delete
            publishDelete(file);
            lastChecksums.remove(file);
            return;
        }
        String checksum = checksum(file);
        String previous = lastChecksums.put(file, checksum);
        if (checksum.equals(previous)) {
            log.debug("skip duplicate modify (same checksum): {}", file);
            return;
        }
        String name = configNameOf(file);
        Object payload = null;
        if (parsePayload) {
            try {
                payload = mapper.readValue(file.toFile(), Object.class);
            } catch (IOException e) {
                log.warn("parse payload failed for {}: {} - publishing event with null payload",
                        file, e.getMessage());
            }
        }
        ConfigChanged event = new ConfigChanged(
                name,
                ConfigChanged.Source.FILE,
                Instant.now().toEpochMilli(),
                payload,
                Map.of("checksum", checksum, "path", file.toString()),
                "system",
                Instant.now()
        );
        bus.publish(event);
        log.info("config file changed: name={} file={} checksum={}", name, file.getFileName(), checksum);
    }

    private void publishDelete(Path file) {
        String name = configNameOf(file);
        ConfigChanged event = new ConfigChanged(
                name,
                ConfigChanged.Source.FILE,
                Instant.now().toEpochMilli(),
                null,
                Map.of("deleted", true, "path", file.toString()),
                "system",
                Instant.now()
        );
        bus.publish(event);
        log.info("config file deleted: name={} file={}", name, file.getFileName());
    }

    /** 从文件路径推导 config name: {@code models.json} → {@code models}。 */
    public static String configNameOf(Path file) {
        String filename = file.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        // history 目录下的 snapshot 也归属同名(name 不带 .json 前缀以外的修饰)
        return stem;
    }

    private static String checksum(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (IOException | NoSuchAlgorithmException e) {
            log.warn("checksum failed for {}: {}", file, e.getMessage());
            return "_error_" + System.nanoTime();
        }
    }

    /** 停止监听。可多次调用,幂等。 */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.warn("close WatchService: {}", e.getMessage());
            }
        }
        if (watcherThread != null) watcherThread.interrupt();
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
        }
        pending.values().forEach(p -> p.future.cancel(false));
        pending.clear();
        log.info("ConfigFileWatcher stopped");
    }

    @Override
    public void close() {
        stop();
    }

    /** 调试/健康检查:当前已发布事件涉及的文件清单。 */
    public Map<String, String> knownChecksums() {
        Map<String, String> out = new java.util.HashMap<>();
        lastChecksums.forEach((p, c) -> out.put(p.toString(), c));
        return out;
    }

    public boolean isRunning() {
        return running;
    }

    /** 受监视的目录(package-private 供 K8sConfigMapWatcher 访问)。 */
    Path dataDir() {
        return dataDir;
    }

    private record PendingEvent(java.util.concurrent.ScheduledFuture<?> future) {}
}