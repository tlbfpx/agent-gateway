package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.ModelId;
import java.util.Set;

/**
 * spec §19.2 ModelPermission。models 必须 ≥ 1，与 AuthPrincipal.allowedModels 字段语义一致。
 */
public record ModelPermission(Set<ModelId> models) implements Permission {
    public ModelPermission {
        if (models == null || models.isEmpty()) {
            throw new IllegalArgumentException("models must contain at least one model");
        }
        models = Set.copyOf(models);
    }
}