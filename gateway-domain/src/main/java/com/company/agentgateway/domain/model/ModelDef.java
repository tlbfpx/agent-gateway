package com.company.agentgateway.domain.model;
import com.company.agentgateway.domain.shared.ModelId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** spec §17.2 权威定义。apiKeyRef 为密钥引用（不落明文）。weight = 灰度权重（0-100，默认 100）。 */
public record ModelDef(ModelId id, String provider, String displayName, String endpoint,
                       String apiKeyRef, Set<Capability> capabilities, int contextWindow,
                       BigDecimal costPer1kIn, BigDecimal costPer1kOut,
                       boolean enabled, List<String> tenantScope,
                       String modelName, int weight) {
    public ModelDef {
        capabilities = Set.copyOf(capabilities);
        tenantScope = List.copyOf(tenantScope);
    }
    /** 厂商真实模型名（缺省=网关 id）。网关 id（如 minimax-abab6.5s-chat）≠ 厂商名（abab6.5s-chat）。 */
    public String modelNameOrId() {
        return (modelName == null || modelName.isBlank()) ? id.value() : modelName;
    }
    /** 灰度权重（spec §5.5 二期；<0 视为 0，>100 视为 100，缺省 100=全量）。 */
    public int normalizedWeight() {
        return Math.max(0, Math.min(100, weight));
    }
    /** 兼容旧 12 参构造（weight 缺省 100）。 */
    public ModelDef(ModelId id, String provider, String displayName, String endpoint,
                    String apiKeyRef, Set<Capability> capabilities, int contextWindow,
                    BigDecimal costPer1kIn, BigDecimal costPer1kOut,
                    boolean enabled, List<String> tenantScope, String modelName) {
        this(id, provider, displayName, endpoint, apiKeyRef, capabilities, contextWindow,
                costPer1kIn, costPer1kOut, enabled, tenantScope, modelName, 100);
    }
    /** 兼容旧 11 参构造（modelName/weight 缺省）。 */
    public ModelDef(ModelId id, String provider, String displayName, String endpoint,
                    String apiKeyRef, Set<Capability> capabilities, int contextWindow,
                    BigDecimal costPer1kIn, BigDecimal costPer1kOut,
                    boolean enabled, List<String> tenantScope) {
        this(id, provider, displayName, endpoint, apiKeyRef, capabilities, contextWindow,
                costPer1kIn, costPer1kOut, enabled, tenantScope, null, 100);
    }
    public boolean supportsFunctionCalling() {
        return capabilities.contains(Capability.FUNCTION_CALLING);
    }
}
