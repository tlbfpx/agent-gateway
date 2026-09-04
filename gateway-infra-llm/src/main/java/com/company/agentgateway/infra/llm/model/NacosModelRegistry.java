package com.company.agentgateway.infra.llm.model;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.company.agentgateway.domain.model.ModelDef;
import com.company.agentgateway.domain.shared.ModelId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 基于 Nacos 配置中心的模型注册表实现。
 * <p>
 * 监听 Nacos 配置变更，实时更新模型定义，并通知注册的监听器。
 * <p>
 * 线程安全性：
 * <ul>
 *   <li>使用 {@link AtomicReference} 保证模型快照的原子性更新</li>
 *   <li>使用 {@link CopyOnWriteArrayList} 保证监听器列表的并发安全</li>
 *   <li>配置变更回调由 Nacos 客户端线程执行，需快速返回避免阻塞</li>
 * </ul>
 */
public class NacosModelRegistry implements ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(NacosModelRegistry.class);

    private final ConfigService configService;
    private final YamlModelConfigParser parser;
    private final String dataId;
    private final String group;
    private final long timeoutMs;

    /** 可选审计(Sprint 1 P0 §3.5);未注入时降级为仅日志 */
    private volatile com.company.agentgateway.domain.audit.AuditRepository auditRepository;

    public void setAuditRepository(com.company.agentgateway.domain.audit.AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    /**
     * 当前模型快照（不可变 Map）
     * 使用 AtomicReference 保证原子性更新
     */
    private final AtomicReference<Map<ModelId, ModelDef>> modelsSnapshot;

    /**
     * 变更监听器列表（线程安全）
     */
    private final List<Consumer<Set<ModelId>>> listeners;

    /**
     * 创建 Nacos 模型注册表。
     *
     * @param configService Nacos ConfigService（由调用方通过 {@link com.alibaba.nacos.client.config.NacosConfigService} 创建）
     * @param parser         YAML 解析器
     * @param dataId         配置 dataId
     * @param group          配置分组
     * @param timeoutMs      首次拉取超时时间（毫秒）
     * @throws NacosException 当首次加载配置失败时
     */
    public NacosModelRegistry(
            ConfigService configService,
            YamlModelConfigParser parser,
            String dataId,
            String group,
            long timeoutMs) throws NacosException {

        this.configService = configService;
        this.parser = parser;
        this.dataId = dataId;
        this.group = group;
        this.timeoutMs = timeoutMs;
        this.modelsSnapshot = new AtomicReference<>(Map.of());
        this.listeners = new CopyOnWriteArrayList<>();

        initialize();
    }

    /**
     * 初始化：首次拉取配置并注册监听器。
     */
    private void initialize() throws NacosException {
        log.info("Initializing NacosModelRegistry: dataId={}, group={}", dataId, group);

        // 1. 首次拉取配置
        String initialConfig = configService.getConfig(dataId, group, timeoutMs);
        if (initialConfig != null && !initialConfig.isBlank()) {
            updateModels(initialConfig);
            log.info("Loaded initial models: count={}", modelsSnapshot.get().size());
        } else {
            log.warn("Initial config is empty, starting with empty model set");
        }

        // 2. 注册配置变更监听器
        configService.addListener(dataId, group, new Listener() {
            @Override
            public Executor getExecutor() {
                // 返回 null，使用 Nacos 默认线程池处理回调
                return null;
            }

            @Override
            public void receiveConfigInfo(String configInfo) {
                handleConfigChange(configInfo);
            }
        });

        log.info("NacosModelRegistry initialized and listening for changes");
    }

    /**
     * 处理配置变更。
     *
     * @param newConfigYaml 新的 YAML 配置
     */
    private void handleConfigChange(String newConfigYaml) {
        try {
            log.debug("Received config change, updating models");
            int before = modelsSnapshot.get().size();
            updateModels(newConfigYaml);
            int after = modelsSnapshot.get().size();
            log.info("Models updated successfully: count={} -> {}", before, after);
            // 审计：Nacos 触发的配置变更(Sprint 1 P0 §3.5)
            appendConfigAudit(after - before, "nacos-models");
        } catch (Exception e) {
            log.error("Failed to update models from config change", e);
            // 不抛出异常，避免 Nacos 监听器循环重试
        }
    }

    private void appendConfigAudit(int delta, String source) {
        if (auditRepository == null) return;
        try {
            auditRepository.append(new com.company.agentgateway.domain.audit.AuditRepository.AuditLog(
                    "nacos-" + System.nanoTime(),
                    new com.company.agentgateway.domain.shared.TenantId("default"),
                    "system",
                    com.company.agentgateway.domain.audit.AuditRepository.AuditLog.ActorType.SYSTEM,
                    com.company.agentgateway.domain.audit.AuditRepository.AuditEventType.MODEL_CONFIG_UPDATE,
                    java.time.Instant.now(),
                    "model",
                    source,
                    "RELOAD",
                    com.company.agentgateway.domain.audit.AuditRepository.AuditLog.Result.SUCCESS,
                    "delta=" + delta));
        } catch (Exception e) {
            log.warn("audit append failed for nacos reload: {}", e.getMessage());
        }
    }

    /**
     * 解析 YAML 并原子更新模型快照，计算变更并通知监听器。
     *
     * @param yamlContent YAML 配置内容
     */
    private void updateModels(String yamlContent) {
        // 解析新模型列表
        List<ModelDef> newModels = parser.parse(yamlContent);
        Map<ModelId, ModelDef> newModelMap = newModels.stream()
                .collect(Collectors.toMap(
                        ModelDef::id,
                        model -> model,
                        (existing, replacement) -> replacement // 重复 ID 取后者
                ));

        // 获取旧模型快照
        Map<ModelId, ModelDef> oldModelMap = modelsSnapshot.get();

        // 计算变更（新增、修改、删除）
        Set<ModelId> changedIds = computeChanges(oldModelMap, newModelMap);

        // 原子替换快照
        modelsSnapshot.set(Collections.unmodifiableMap(newModelMap));

        // 通知监听器（如有变更）
        if (!changedIds.isEmpty()) {
            notifyListeners(changedIds);
        }
    }

    /**
     * 计算模型变更集合。
     *
     * @param oldMap 旧模型 Map
     * @param newMap 新模型 Map
     * @return 变更的 ModelId 集合
     */
    private Set<ModelId> computeChanges(Map<ModelId, ModelDef> oldMap, Map<ModelId, ModelDef> newMap) {
        Set<ModelId> changed = new HashSet<>();

        // 检查新增和修改
        for (Map.Entry<ModelId, ModelDef> entry : newMap.entrySet()) {
            ModelId id = entry.getKey();
            ModelDef newModel = entry.getValue();
            ModelDef oldModel = oldMap.get(id);

            if (oldModel == null) {
                // 新增
                changed.add(id);
                log.debug("Model added: {}", id);
            } else if (!isModelEqual(oldModel, newModel)) {
                // 修改
                changed.add(id);
                log.debug("Model modified: {}", id);
            }
        }

        // 检查删除
        for (ModelId id : oldMap.keySet()) {
            if (!newMap.containsKey(id)) {
                changed.add(id);
                log.debug("Model removed: {}", id);
            }
        }

        return changed;
    }

    /**
     * 比较两个 ModelDef 是否相等（用于判断是否需要通知变更）。
     * 只比较关键字段，避免因实现细节变化导致不必要的通知。
     */
    private boolean isModelEqual(ModelDef m1, ModelDef m2) {
        return Objects.equals(m1.id(), m2.id()) &&
                Objects.equals(m1.provider(), m2.provider()) &&
                Objects.equals(m1.displayName(), m2.displayName()) &&
                Objects.equals(m1.endpoint(), m2.endpoint()) &&
                Objects.equals(m1.apiKeyRef(), m2.apiKeyRef()) &&
                Objects.equals(m1.capabilities(), m2.capabilities()) &&
                Objects.equals(m1.contextWindow(), m2.contextWindow()) &&
                Objects.equals(m1.costPer1kIn(), m2.costPer1kIn()) &&
                Objects.equals(m1.costPer1kOut(), m2.costPer1kOut()) &&
                Objects.equals(m1.enabled(), m2.enabled()) &&
                Objects.equals(m1.tenantScope(), m2.tenantScope());
    }

    /**
     * 通知所有监听器。
     *
     * @param changedIds 变更的 ModelId 集合
     */
    private void notifyListeners(Set<ModelId> changedIds) {
        for (Consumer<Set<ModelId>> listener : listeners) {
            try {
                listener.accept(changedIds);
            } catch (Exception e) {
                log.error("Listener callback failed", e);
                // 继续通知其他监听器
            }
        }
    }

    @Override
    public Optional<ModelDef> getModel(ModelId id) {
        Map<ModelId, ModelDef> snapshot = modelsSnapshot.get();
        return Optional.ofNullable(snapshot.get(id));
    }

    @Override
    public List<ModelDef> listModels() {
        Map<ModelId, ModelDef> snapshot = modelsSnapshot.get();
        return List.copyOf(snapshot.values());
    }

    @Override
    public void addListener(Consumer<Set<ModelId>> listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener cannot be null");
        }
        listeners.add(listener);
        log.debug("Listener added, total listeners: {}", listeners.size());
    }
}
