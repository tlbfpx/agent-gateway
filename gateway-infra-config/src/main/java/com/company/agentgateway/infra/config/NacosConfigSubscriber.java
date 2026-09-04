package com.company.agentgateway.infra.config;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.company.agentgateway.domain.config.ConfigChanged;
import com.company.agentgateway.domain.config.ConfigReloadBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 通用 Nacos Config 订阅器(Sprint 1 P0 §3.6):把任意 dataId + group 的 YAML/JSON
 * 配置推送到 {@link ConfigReloadBus}。多 dataId 场景下,每个配置名各自一个实例。
 *
 * <p>原 {@code NacosModelRegistry} 仅支持 models;本期抽出本通用订阅器后,
 * webhook / rbac / mcp-server / rate-limit 都能复用同一套热更新 + 审计路径。
 *
 * <h2>契约</h2>
 * <ul>
 *   <li>构造时立即同步拉取首版配置 → publish 到 bus</li>
 *   <li>Nacos push 回调 → publish 到 bus(name + source = NACOS + actor = system)</li>
 *   <li>payload 解析失败仅告警,不抛出(Nacos 客户端线程,避免拖死回调)</li>
 *   <li>提供 {@link #close()} 释放资源(单元测试友好)</li>
 * </ul>
 *
 * <p>依赖:Nacos client + Jackson + ConfigReloadBus。无 Spring 注解,纯 POJO,
 * 由 Spring 配置装配。
 */
public class NacosConfigSubscriber implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NacosConfigSubscriber.class);

    private final ConfigService configService;
    private final ConfigReloadBus bus;
    private final ObjectMapper mapper;
    private final String name;
    private final String dataId;
    private final String group;
    private final long timeoutMs;
    private final boolean parsePayload;

    private Listener listener;
    private volatile boolean closed = false;

    public NacosConfigSubscriber(ConfigService configService, ConfigReloadBus bus, ObjectMapper mapper,
                                 String name, String dataId, String group, long timeoutMs,
                                 boolean parsePayload) {
        this.configService = configService;
        this.bus = bus;
        this.mapper = mapper;
        this.name = name;
        this.dataId = dataId;
        this.group = group;
        this.timeoutMs = timeoutMs;
        this.parsePayload = parsePayload;
    }

    /** 同步拉取首版配置 + 注册 push 监听器。失败抛 NacosException(由装配层决定降级)。 */
    public void start() throws NacosException {
        // 1. 首版拉取
        String initial = configService.getConfig(dataId, group, timeoutMs);
        if (initial != null && !initial.isBlank()) {
            publishToBus(initial, System.currentTimeMillis());
        } else {
            log.warn("Nacos config initial empty: dataId={} group={}", dataId, group);
        }

        // 2. 注册 push 监听器
        listener = new Listener() {
            @Override public Executor getExecutor() { return null; /* null = Nacos 默认线程 */ }
            @Override public void receiveConfigInfo(String configInfo) {
                publishToBus(configInfo, System.currentTimeMillis());
            }
        };
        configService.addListener(dataId, group, listener);
        log.info("NacosConfigSubscriber started: name={} dataId={} group={}", name, dataId, group);
    }

    private void publishToBus(String content, long version) {
        if (closed) return;
        try {
            Object payload = null;
            if (parsePayload && content != null && !content.isBlank()) {
                try {
                    payload = mapper.readValue(content, Object.class);
                } catch (Exception parseEx) {
                    log.warn("NacosConfigSubscriber parse failed for {}: {}", name, parseEx.getMessage());
                    payload = null; // 仍发事件,payload=null 即可
                }
            }
            ConfigChanged event = new ConfigChanged(
                    name,
                    ConfigChanged.Source.NACOS,
                    version,
                    payload,
                    Map.of("dataId", dataId, "group", group, "size", content == null ? 0 : content.length()),
                    "system",
                    java.time.Instant.now()
            );
            bus.publish(event);
            log.info("nacos push published to bus: name={} version={} size={}",
                    name, version, content == null ? 0 : content.length());
        } catch (RuntimeException ex) {
            log.error("publishToBus failed for name={}: {}", name, ex.getMessage(), ex);
        }
    }

    @Override
    public void close() {
        closed = true;
        if (listener != null) {
            try {
                configService.removeListener(dataId, group, listener);
            } catch (Exception e) {
                log.warn("removeListener failed: {}", e.getMessage());
            }
            listener = null;
        }
        log.info("NacosConfigSubscriber closed: name={}", name);
    }

    public String name() { return name; }
    public String dataId() { return dataId; }
    public String group() { return group; }
}