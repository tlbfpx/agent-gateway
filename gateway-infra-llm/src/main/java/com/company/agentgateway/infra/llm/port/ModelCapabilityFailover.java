package com.company.agentgateway.infra.llm.port;

import com.company.agentgateway.domain.model.ModelDef;
import com.company.agentgateway.domain.orchestration.ToolDescriptor;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.infra.llm.model.ModelRegistry;

import java.util.List;

/**
 * spec §5.5.5 能力降级：用户选定模型缺 FUNCTION_CALLING 却需调用工具（!tools.isEmpty()）时，
 * 自动 failover 到 fallbackToolModel（一期唯一策略，选项 A）。
 *
 * <p>校验：fallback 必须存在且自身支持 FUNCTION_CALLING，否则抛异常。
 * fallback 在 Nacos 未就绪时可能延迟到首次 resolve 才校验（对开发态友好）。
 */
public class ModelCapabilityFailover {

    private final ModelRegistry registry;
    private final ModelId fallbackToolModelId;

    public ModelCapabilityFailover(ModelRegistry registry, ModelId fallbackToolModelId) {
        this.registry = registry;
        this.fallbackToolModelId = fallbackToolModelId;
    }

    /**
     * 解析最终使用的模型。
     *
     * @param selected 用户/会话选定的模型
     * @param tools    本次需要的工具（非空则需要 function-calling）
     * @return 最终模型（selected 或 fallback）
     * @throws IllegalArgumentException fallback 不存在
     * @throws IllegalStateException    fallback 自身不支持 FUNCTION_CALLING
     */
    public ModelDef resolve(ModelDef selected, List<ToolDescriptor> tools) {
        if (tools.isEmpty() || selected.supportsFunctionCalling()) {
            return selected;
        }
        // 需工具但 selected 缺 FUNCTION_CALLING → failover（未配置 fallback 时直接用原模型，可插拔：不强制）
        if (fallbackToolModelId == null) {
            return selected;
        }
        ModelDef fallback = registry.getModel(fallbackToolModelId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "fallbackToolModel not found: " + fallbackToolModelId));
        if (!fallback.supportsFunctionCalling()) {
            throw new IllegalStateException(
                    "fallbackToolModel must support FUNCTION_CALLING: " + fallbackToolModelId);
        }
        return fallback;
    }
}
