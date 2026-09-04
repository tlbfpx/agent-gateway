package com.company.agentgateway.infra.llm.port;

import com.company.agentgateway.domain.model.ModelDef;
import com.company.agentgateway.domain.shared.ModelId;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 模型灰度路由（spec §5.5 二期）：请求指定模型 id 时，若注册表存在「同 displayName 灰度组」
 * （如 glm-4-plus 组内 glm-4-plus 与 glm-4-plus-canary），按 normalizedWeight 加权随机分流。
 *
 * <p>组定义：同 displayName 的全部 enabled 模型。权重 0 = 不分流到，100 = 全量。
 * 无组（唯一成员）或请求的 id 不在组内时，精确匹配该 id（行为与灰度前完全一致——兼容）。
 */
public class WeightedModelSelector {

    private final java.util.function.Function<ModelId, java.util.Optional<ModelDef>> registry;

    public WeightedModelSelector(java.util.function.Function<ModelId, java.util.Optional<ModelDef>> registry) {
        this.registry = registry;
    }

    /** 选择实际使用的模型（灰度组加权 / 精确匹配兜底）。 */
    public ModelDef select(ModelId requested, List<ModelDef> allModels) {
        ModelDef target = registry.apply(requested).orElse(null);
        if (target == null) return null;

        List<ModelDef> group = allModels.stream()
                .filter(m -> m.enabled())
                .filter(m -> m.displayName() != null && m.displayName().equals(target.displayName()))
                .filter(m -> m.normalizedWeight() > 0)
                .toList();
        if (group.size() <= 1) {
            // 无有效灰度组：唯一成员即目标（可能是组内唯一有权重者，未必是请求的 id）
            ModelDef sole = group.isEmpty() ? target : group.get(0);
            return sole.enabled() ? sole : null;
        }

        int total = group.stream().mapToInt(ModelDef::normalizedWeight).sum();
        if (total <= 0) return target.enabled() ? target : null;

        int dice = ThreadLocalRandom.current().nextInt(total);
        int acc = 0;
        for (ModelDef m : group) {
            acc += m.normalizedWeight();
            if (dice < acc) return m;
        }
        return group.get(group.size() - 1); // 数值边界兜底
    }
}
