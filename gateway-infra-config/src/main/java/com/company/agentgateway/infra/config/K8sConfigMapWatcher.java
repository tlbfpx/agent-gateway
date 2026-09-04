package com.company.agentgateway.infra.config;

import com.company.agentgateway.domain.config.ConfigChanged;
import com.company.agentgateway.domain.config.ConfigReloadBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * K8s ConfigMap 卷挂载监听器(Sprint 1 P0 §3.7):
 * 复用 {@link ConfigFileWatcher} 监听 {@code /etc/gateway-config/*.yaml},
 * 把 NIO 事件翻译为 ConfigReloadBus 事件,source 标记为 K8S_CONFIGMAP。
 *
 * <p>为什么不依赖 K8s client:in-cluster client 需要 RBAC 权限;卷挂载 + fsnotify
 * 完全够用且零额外依赖。Helm chart 默认把 ConfigMap 挂到 {@code /etc/gateway-config}。
 *
 * <h2>触发链路</h2>
 * <pre>
 * kubectl edit cm gateway-config → kubelet 重写挂载文件
 *  → NIO inotify/FSEvents 触发
 *  → ConfigFileWatcher.scheduleDebounced
 *  → 本类改写 source 为 K8S_CONFIGMAP 后 publish 到 bus
 *  → 各 store reload
 * </pre>
 *
 * <p><b>注意</b>:本类只做"事件 source 标记改写",真正的文件监听复用 {@link ConfigFileWatcher};
 * 如果未来需要"非路径触发"(如直接 watch K8s API),应独立实现而非继承。
 */
public class K8sConfigMapWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(K8sConfigMapWatcher.class);
    public static final Path DEFAULT_MOUNT_PATH = Path.of("/etc/gateway-config");

    private final ConfigFileWatcher delegate;
    private final ConfigReloadBus bus;

    public K8sConfigMapWatcher(Path mountPath, ConfigReloadBus bus, ObjectMapper mapper) {
        this(mountPath, bus, mapper, ConfigFileWatcher.DEFAULT_DEBOUNCE_MS);
    }

    public K8sConfigMapWatcher(Path mountPath, ConfigReloadBus bus, ObjectMapper mapper, long debounceMs) {
        this.bus = bus;
        this.delegate = new ConfigFileWatcher(mountPath, bus, mapper, debounceMs, true);
        // 重写派发:订阅原事件的 * 桶,把源于本挂载路径的 FILE 事件改写为 K8S_CONFIGMAP
        bus.subscribe("*", event -> {
            if (event.source() == ConfigChanged.Source.FILE
                    && event.summary() != null
                    && event.summary().get("path") instanceof String pathStr
                    && pathStr.startsWith(mountPath.toString())) {
                ConfigChanged rewritten = new ConfigChanged(
                        event.name(),
                        ConfigChanged.Source.K8S_CONFIGMAP,
                        event.version(),
                        event.payload(),
                        event.summary(),
                        "system",
                        event.occurredAt());
                bus.publish(rewritten);
                log.debug("k8s-configmap rewrite: name={} path={}", event.name(), pathStr);
            }
        });
    }

    @Override
    public void close() {
        stop();
    }

    public void start() throws IOException {
        delegate.start();
        log.info("K8sConfigMapWatcher started: mountPath={}", mountPath());
    }

    public void stop() {
        delegate.stop();
        log.info("K8sConfigMapWatcher stopped");
    }

    public Path mountPath() {
        return delegate.dataDir(); // 字段访问通过 package-private getter(见下)
    }

    public boolean isRunning() {
        return delegate.isRunning();
    }
}