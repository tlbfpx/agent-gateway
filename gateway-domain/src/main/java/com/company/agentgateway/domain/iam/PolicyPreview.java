package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import java.util.Set;

/**
 * spec §19.2 PolicyPreview。dry-run 预览结果：用户在某租户下实际可用的
 * Agent / 模型集合（D1-2 决策：纯函数重放，不命中任何缓存，不写审计）。
 */
public record PolicyPreview(UserId user, TenantId tenant,
                            Set<String> allowedAgents, Set<ModelId> allowedModels) {
    public PolicyPreview {
        allowedAgents = Set.copyOf(allowedAgents);
        allowedModels = Set.copyOf(allowedModels);
    }
}
