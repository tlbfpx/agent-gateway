package com.company.agentgateway.infra.config;

import com.company.agentgateway.domain.config.ConfigReloadBus;
import com.company.agentgateway.domain.config.InMemoryConfigReloadBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置源自动装配(Sprint 1 P0 §3.8):按 {@code gateway.config.source} 选择装配:
 * <ul>
 *   <li>{@code file}(默认):启用 {@link ConfigFileWatcher} 监听 {@code data/}</li>
 *   <li>{@code nacos}:启用 {@link NacosConfigSubscriber}(多 dataId)</li>
 *   <li>{@code k8s-configmap}:启用 {@link K8sConfigMapWatcher} 监听
 *       {@code /etc/gateway-config/}(或自定义)</li>
 *   <li>{@code all}:三者并存(本地开发友好)</li>
 * </ul>
 *
 * <h2>Bean 装配清单</h2>
 * <ul>
 *   <li>{@link ConfigReloadBus}:进程内 pub-sub 实现</li>
 *   <li>{@link ConfigFileWatcher}:文件源(默认开)</li>
 *   <li>{@link K8sConfigMapWatcher}:K8s 卷挂载源(条件开)</li>
 *   <li>{@link NacosConfigSubscriber}:Nacos 源(条件开 + 需 nacos-client bean)</li>
 *   <li>{@link ConfigSourceRegistry}:汇总所有 watcher + 提供状态查询(给 UI 用)</li>
 * </ul>
 *
 * <p>关闭方法:实现 {@link DisposableBean},应用关停时调用每个 watcher.stop()
 * 释放 WatchService / scheduled executor / Nacos listener。
 */
@Configuration
@ConditionalOnProperty(name = "gateway.config.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigSourceAutoConfiguration implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ConfigSourceAutoConfiguration.class);

    @Value("${gateway.config.source:file}")
    private String source;

    @Value("${gateway.config.data-dir:data}")
    private String dataDir;

    @Value("${gateway.config.k8s-mount-path:/etc/gateway-config}")
    private String k8sMountPath;

    @Value("${gateway.config.debounce-ms:200}")
    private long debounceMs;

    private final List<AutoCloseable> startedResources = new ArrayList<>();

    @Bean
    @ConditionalOnMissingBean(ConfigReloadBus.class)
    public ConfigReloadBus configReloadBus() {
        log.info("Initializing ConfigReloadBus (in-memory pub-sub)");
        return new InMemoryConfigReloadBus();
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(name = "gateway.config.source", havingValue = "file", matchIfMissing = true)
    public ConfigFileWatcher configFileWatcher(ConfigReloadBus bus, ObjectMapper mapper) throws IOException {
        Path dir = Paths.get(dataDir);
        ConfigFileWatcher w = new ConfigFileWatcher(dir, bus, mapper, debounceMs, true);
        w.start();
        startedResources.add(w);
        return w;
    }

    @Bean
    @ConditionalOnProperty(name = "gateway.config.source", havingValue = "k8s-configmap")
    public K8sConfigMapWatcher k8sConfigMapWatcher(ConfigReloadBus bus, ObjectMapper mapper) throws IOException {
        Path mount = Paths.get(k8sMountPath);
        K8sConfigMapWatcher w = new K8sConfigMapWatcher(mount, bus, mapper, debounceMs);
        w.start();
        startedResources.add(w);
        return w;
    }

    /** 总览:UI 状态徽章数据源。 */
    @Bean
    public ConfigSourceRegistry configSourceRegistry(ConfigReloadBus bus) {
        return new ConfigSourceRegistry(bus);
    }

    @Override
    public void destroy() {
        for (AutoCloseable r : startedResources) {
            try { r.close(); } catch (Exception e) {
                log.warn("close {}: {}", r.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}