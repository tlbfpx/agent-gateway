package com.company.agentgateway.infra.llm.model;

import com.company.agentgateway.domain.model.Capability;
import com.company.agentgateway.domain.model.ModelDef;
import com.company.agentgateway.domain.shared.ModelId;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 可写模型注册表：管理员经 REST 动态增删改模型，持久化到本地 JSON 文件（重启不丢）。
 *
 * <p>文件格式（{@code gateway.llm.registry-file}，默认 data/models.json）：
 * <pre>
 * [ {"id":"minimax-abab6.5s-chat","provider":"minimax","modelName":"abab6.5s-chat",
 *    "endpoint":"https://api.minimax.chat","apiKey":"sk-...","capabilities":["FUNCTION_CALLING"],...} ]
 * </pre>
 *
 * <p>写路径：内存 Map 先更新 → 原子写文件（tmp + move）。读路径：启动加载。
 * 变更通知 listeners（ChatClientFactory 缓存失效）。
 * fail-safe：单条模型损坏跳过不拖垮全部；文件损坏时空表启动（不阻塞应用）。
 */
public class JsonFileModelRegistry implements ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(JsonFileModelRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Map<ModelId, ModelDef> models = new ConcurrentHashMap<>();
    private final List<Consumer<Set<ModelId>>> listeners = new CopyOnWriteArrayList<>();

    public JsonFileModelRegistry(Path file) {
        this.file = file;
        load();
    }

    /** 启动加载（文件不存在则空表）。 */
    private void load() {
        if (!Files.exists(file)) {
            log.info("Model registry file {} not found, starting empty", file);
            return;
        }
        try {
            CollectionType type = MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, Map.class);
            List<Map<String, Object>> raw = MAPPER.readValue(file.toFile(), type);
            raw.forEach(this::putFromMap);
            log.info("Model registry loaded {} model(s) from {}", models.size(), file);
        } catch (Exception e) {
            log.error("Failed to load model registry {} (starting empty): {}", file, e.getMessage());
        }
    }

    /** 新增或更新模型（upsert），持久化并通知。 */
    public ModelDef upsert(ModelDef def) {
        models.put(def.id(), def);
        persist();
        notifyListeners(Set.of(def.id()));
        return def;
    }

    /** 删除模型。返回是否删除。 */
    public boolean delete(ModelId id) {
        ModelDef removed = models.remove(id);
        if (removed != null) {
            persist();
            notifyListeners(Set.of(id));
        }
        return removed != null;
    }

    @Override
    public Optional<ModelDef> getModel(ModelId id) {
        return Optional.ofNullable(models.get(id));
    }

    @Override
    public List<ModelDef> listModels() {
        return List.copyOf(models.values());
    }

    @Override
    public void addListener(Consumer<Set<ModelId>> listener) {
        listeners.add(listener);
    }

    private void notifyListeners(Set<ModelId> changed) {
        listeners.forEach(l -> {
            try {
                l.accept(changed);
            } catch (Exception e) {
                log.warn("Model registry listener failed: {}", e.getMessage());
            }
        });
    }

    /** 原子持久化（写 tmp 后 move）。失败仅告警——内存已是最新，下次写成功即恢复。 */
    /** 写盘后回调（装配层注入 ConfigHistory.snapshot；缺省无操作）。 */
    private Runnable onPersist = () -> {};

    public void setOnPersist(Runnable r) { this.onPersist = r; }

    private synchronized void persist() {
        try {
            Files.createDirectories(file.getParent());
            List<Map<String, Object>> out = models.values().stream()
                    .map(JsonFileModelRegistry::toMap)
                    .collect(Collectors.toList());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), out);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Failed to persist model registry to {}: {}", file, e.getMessage());
            return;
        }
        onPersist.run();
    }

    private void putFromMap(Map<String, Object> cfg) {
        try {
            String id = str(cfg, "id");
            String provider = str(cfg, "provider");
            if (id == null || provider == null) {
                log.warn("Skip model entry without id/provider: {}", cfg);
                return;
            }
            ModelDef def = new ModelDef(
                    new ModelId(id),
                    provider,
                    strOr(cfg, "displayName", id),
                    strOr(cfg, "endpoint", ""),
                    strOr(cfg, "apiKey", strOr(cfg, "apiKeyRef", "")),
                    caps(cfg.get("capabilities")),
                    intOr(cfg, "contextWindow", 8192),
                    decOr(cfg, "costPer1kIn", BigDecimal.ZERO),
                    decOr(cfg, "costPer1kOut", BigDecimal.ZERO),
                    boolOr(cfg, "enabled", true),
                    listOr(cfg, "tenantScope", List.of("all")),
                    str(cfg, "modelName"),
                    intOr(cfg, "weight", 100));
            models.put(def.id(), def);
        } catch (Exception e) {
            log.warn("Skip bad model entry {}: {}", cfg.get("id"), e.getMessage());
        }
    }

    private static Map<String, Object> toMap(ModelDef d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id().value());
        m.put("provider", d.provider());
        m.put("displayName", d.displayName());
        m.put("endpoint", d.endpoint());
        m.put("apiKey", d.apiKeyRef());
        m.put("capabilities", d.capabilities().stream().map(Enum::name).toList());
        m.put("contextWindow", d.contextWindow());
        m.put("costPer1kIn", d.costPer1kIn().toPlainString());
        m.put("costPer1kOut", d.costPer1kOut().toPlainString());
        m.put("enabled", d.enabled());
        m.put("tenantScope", d.tenantScope());
        if (d.modelName() != null && !d.modelName().isBlank()) {
            m.put("modelName", d.modelName());
        }
        if (d.normalizedWeight() != 100) {
            m.put("weight", d.normalizedWeight());
        }
        return m;
    }

    // ─── 解析辅助 ───

    private static String str(Map<String, Object> cfg, String k) {
        Object v = cfg.get(k);
        return v == null ? null : String.valueOf(v).trim();
    }

    private static String strOr(Map<String, Object> cfg, String k, String dft) {
        String v = str(cfg, k);
        return (v == null || v.isEmpty()) ? dft : v;
    }

    private static int intOr(Map<String, Object> cfg, String k, int dft) {
        try {
            return Integer.parseInt(String.valueOf(cfg.get(k)));
        } catch (Exception e) {
            return dft;
        }
    }

    private static BigDecimal decOr(Map<String, Object> cfg, String k, BigDecimal dft) {
        try {
            return new BigDecimal(String.valueOf(cfg.get(k)));
        } catch (Exception e) {
            return dft;
        }
    }

    private static boolean boolOr(Map<String, Object> cfg, String k, boolean dft) {
        Object v = cfg.get(k);
        return v == null ? dft : Boolean.parseBoolean(String.valueOf(v));
    }

    private static List<String> listOr(Map<String, Object> cfg, String k, List<String> dft) {
        if (cfg.get(k) instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return dft;
    }

    private static Set<Capability> caps(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return Set.of();
        }
        return list.stream()
                .map(String::valueOf)
                .map(s -> {
                    try {
                        return Capability.valueOf(s.trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }
}
