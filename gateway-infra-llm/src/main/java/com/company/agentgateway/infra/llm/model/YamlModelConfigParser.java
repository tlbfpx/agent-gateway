package com.company.agentgateway.infra.llm.model;

import com.company.agentgateway.domain.model.Capability;
import com.company.agentgateway.domain.model.ModelDef;
import com.company.agentgateway.domain.shared.ModelId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 使用 SnakeYAML 解析模型配置 YAML 内容。
 * YAML 格式示例：
 * <pre>
 * models:
 *   - id: deepseek-chat
 *     provider: deepseek
 *     displayName: DeepSeek Chat
 *     endpoint: https://api.deepseek.com
 *     apiKeyRef: ${SECRET:DEEPSEEK_API_KEY}
 *     capabilities: [FUNCTION_CALLING]
 *     contextWindow: 64000
 *     costPer1kIn: 0.14
 *     costPer1kOut: 0.28
 *     enabled: true
 *     tenantScope: [all]
 * </pre>
 */
public class YamlModelConfigParser {

    private static final Logger log = LoggerFactory.getLogger(YamlModelConfigParser.class);

    private final Yaml yaml = new Yaml();

    /**
     * 解析 YAML 内容为模型定义列表。
     *
     * @param yamlContent YAML 字符串
     * @return 模型定义列表
     * @throws IllegalArgumentException 当 YAML 格式错误或缺少必填字段时
     */
    @SuppressWarnings("unchecked")
    public List<ModelDef> parse(String yamlContent) {
        try {
            // 空/blank/null → 空列表（合法的「无模型」语义，而非解析错误）
            if (yamlContent == null || yamlContent.isBlank()) {
                return List.of();
            }

            Object loaded = yaml.load(yamlContent);

            // 标量/列表等非预期根 → 解析错误（抛明确异常，而非 NPE）
            if (!(loaded instanceof Map)) {
                throw new IllegalArgumentException("Failed to parse YAML: expected a map at root but got "
                        + (loaded == null ? "null" : loaded.getClass().getSimpleName()));
            }

            Map<String, Object> root = (Map<String, Object>) loaded;
            Object modelsObj = root.get("models");

            if (modelsObj == null) {
                throw new IllegalArgumentException("Missing 'models' field in YAML");
            }

            if (!(modelsObj instanceof List<?> modelsList)) {
                throw new IllegalArgumentException("'models' must be a list");
            }

            List<ModelDef> result = new ArrayList<>();
            for (Object item : modelsList) {
                if (!(item instanceof Map<?, ?> modelMap)) {
                    throw new IllegalArgumentException("Each model must be a map/dictionary");
                }
                result.add(parseModelDef(modelMap));
            }

            return result;
        } catch (YAMLException e) {
            throw new IllegalArgumentException("Failed to parse YAML: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private ModelDef parseModelDef(Map<?, ?> rawMap) {
        Map<String, Object> map = (Map<String, Object>) rawMap;

        // 必填字段校验
        String id = getRequiredString(map, "id");
        String provider = getRequiredString(map, "provider");
        String displayName = getRequiredString(map, "displayName");
        String endpoint = getRequiredString(map, "endpoint");
        String apiKeyRef = getRequiredString(map, "apiKeyRef");
        Integer contextWindow = getRequiredInt(map, "contextWindow");
        BigDecimal costPer1kIn = getRequiredBigDecimal(map, "costPer1kIn");
        BigDecimal costPer1kOut = getRequiredBigDecimal(map, "costPer1kOut");
        Boolean enabled = getRequiredBoolean(map, "enabled");
        List<String> tenantScope = getRequiredList(map, "tenantScope");

        // 可选字段
        Set<Capability> capabilities = parseCapabilities(map.get("capabilities"));

        return new ModelDef(
                new ModelId(id),
                provider,
                displayName,
                endpoint,
                apiKeyRef,
                capabilities,
                contextWindow,
                costPer1kIn,
                costPer1kOut,
                enabled,
                tenantScope
        );
    }

    private Set<Capability> parseCapabilities(Object capabilitiesObj) {
        if (capabilitiesObj == null) {
            return Set.of();
        }

        if (!(capabilitiesObj instanceof List<?> capList)) {
            throw new IllegalArgumentException("'capabilities' must be a list");
        }

        return capList.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(capStr -> {
                    try {
                        return Capability.valueOf(capStr);
                    } catch (IllegalArgumentException e) {
                        log.warn("Unknown capability '{}', ignoring", capStr);
                        return null;
                    }
                })
                .filter(cap -> cap != null)
                .collect(Collectors.toSet());
    }

    private String getRequiredString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return value.toString();
    }

    private Integer getRequiredInt(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("Field '" + key + "' must be a number");
    }

    private BigDecimal getRequiredBigDecimal(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        // SnakeYAML 可能解析为 Double 或 Integer，统一转 String 再构造 BigDecimal
        return new BigDecimal(String.valueOf(value));
    }

    private Boolean getRequiredBoolean(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new IllegalArgumentException("Field '" + key + "' must be a boolean");
    }

    @SuppressWarnings("unchecked")
    private List<String> getRequiredList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Field '" + key + "' must be a list");
        }
        return list.stream()
                .map(Object::toString)
                .toList();
    }
}
