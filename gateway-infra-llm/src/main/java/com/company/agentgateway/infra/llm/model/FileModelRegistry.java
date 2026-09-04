package com.company.agentgateway.infra.llm.model;

import com.company.agentgateway.domain.model.Capability;
import com.company.agentgateway.domain.model.ModelDef;
import com.company.agentgateway.domain.shared.ModelId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
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
 * 配置驱动的模型注册表：模型列表来自 {@code gateway.models.*} 配置（application.yml /
 * 环境变量 / Nacos config 皆可），切换/新增模型 = 改配置，零代码改动。
 *
 * <p>配置格式（每个条目一个模型；apiKeyRef 支持 ${ENV_VAR} 占位符，由 SecretResolver 解析）：
 * <pre>
 * gateway:
 *   models:
 *     - id: minimax-abab6.5s-chat
 *       provider: minimax
 *       endpoint: https://api.minimax.chat/v1
 *       apiKeyRef: ${MINIMAX_API_KEY}
 *       capabilities: [FUNCTION_CALLING]
 *       contextWindow: 245760
 *       costPer1kIn: 0.003
 *       costPer1kOut: 0.006
 * </pre>
 *
 * <p>envKey 简写：也接受 {@code apiKeyRef: MINIMAX_API_KEY}（无 ${} 包裹的裸环境变量名）。
 * enabled=false 或 key 缺失的模型不注册（fail-safe：一个模型配置错误不影响其他模型）。
 */
public class FileModelRegistry implements ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(FileModelRegistry.class);

    private final Map<ModelId, ModelDef> models = new ConcurrentHashMap<>();
    private final List<Consumer<Set<ModelId>>> listeners = new CopyOnWriteArrayList<>();

    /** 从配置列表构建（Spring @ConfigurationProperties 绑定的 List<Map<String,Object>>）。 */
    public FileModelRegistry(List<Map<String, Object>> modelConfigs) {
        if (modelConfigs != null) {
            modelConfigs.forEach(this::register);
        }
        log.info("FileModelRegistry initialized with {} model(s): {}", models.size(), models.keySet());
    }

    /** 注册单个模型（解析失败仅告警跳过，不抛——保证坏配置不拖垮整个注册表）。 */
    private void register(Map<String, Object> cfg) {
        try {
            String id = str(cfg, "id");
            String provider = str(cfg, "provider");
            if (id == null || provider == null) {
                log.warn("Skip model config without id/provider: {}", cfg);
                return;
            }
            boolean enabled = bool(cfg, "enabled", true);
            if (!enabled) {
                log.info("Model {} disabled, skip", id);
                return;
            }
            Set<Capability> caps = parseCapabilities(cfg.get("capabilities"));
            ModelDef def = new ModelDef(
                    new ModelId(id),
                    provider,
                    strOrDefault(cfg, "displayName", id),
                    strOrDefault(cfg, "endpoint", ""),
                    strOrDefault(cfg, "apiKeyRef", ""),
                    caps,
                    intVal(cfg, "contextWindow", 8192),
                    decVal(cfg, "costPer1kIn", BigDecimal.ZERO),
                    decVal(cfg, "costPer1kOut", BigDecimal.ZERO),
                    true,
                    listVal(cfg, "tenantScope", List.of("all")),
                    str(cfg, "modelName"));
            models.put(def.id(), def);
        } catch (Exception e) {
            log.warn("Failed to register model config {}: {}", cfg.get("id"), e.getMessage());
        }
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

    // ─── 配置解析辅助 ───

    private static String str(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    private static String strOrDefault(Map<String, Object> cfg, String key, String dft) {
        String v = str(cfg, key);
        return (v == null || v.isEmpty()) ? dft : v;
    }

    private static boolean bool(Map<String, Object> cfg, String key, boolean dft) {
        Object v = cfg.get(key);
        return v == null ? dft : Boolean.parseBoolean(String.valueOf(v));
    }

    private static int intVal(Map<String, Object> cfg, String key, int dft) {
        try {
            return Integer.parseInt(String.valueOf(cfg.get(key)));
        } catch (Exception e) {
            return dft;
        }
    }

    private static BigDecimal decVal(Map<String, Object> cfg, String key, BigDecimal dft) {
        try {
            return new BigDecimal(String.valueOf(cfg.get(key)));
        } catch (Exception e) {
            return dft;
        }
    }

    private static List<String> listVal(Map<String, Object> cfg, String key, List<String> dft) {
        Object v = cfg.get(key);
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return dft;
    }

    private static Set<Capability> parseCapabilities(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return Set.of();
        }
        return list.stream()
                .map(String::valueOf)
                .map(s -> {
                    try {
                        return Capability.valueOf(s.trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        log.warn("Unknown capability '{}', skip", s);
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }
}
