package com.company.agentgateway.domain.dataset;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测数据集（spec 2026-09-02 §dataset-eval §3.1）。
 *
 * <p>一组 {@link EvalCase} 的容器;每个 case 含 input/expectedOutput/metadata。
 */
public record EvalDataset(
        long id,
        String name,
        String description,
        String tenantId,
        long ownerId,
        List<String> tags,
        Instant createdAt) {

    public EvalDataset {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (ownerId <= 0) {
            throw new IllegalArgumentException("ownerId must be > 0, got " + ownerId);
        }
        tags = tags == null ? List.of() : List.copyOf(tags);
        if (createdAt == null) createdAt = Instant.now();
    }

    public static EvalDataset create(
            String name, String description, String tenantId, long ownerId, List<String> tags) {
        return new EvalDataset(0L, name, description, tenantId, ownerId, tags, null);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("description", description == null ? "" : description);
        m.put("tenantId", tenantId);
        m.put("ownerId", ownerId);
        m.put("tags", tags);
        m.put("createdAt", createdAt.toString());
        return m;
    }
}
