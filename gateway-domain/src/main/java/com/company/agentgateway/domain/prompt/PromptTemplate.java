package com.company.agentgateway.domain.prompt;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt 模板（spec 2026-09-02 §prompt-version §3.1）。
 *
 * <p>一个 Template 包含多个 Version;Template 本身只存元数据(name/description/tags/owner)。
 * 不可变 record。
 */
public record PromptTemplate(
        long id,
        String name,
        String description,
        long ownerId,
        String tenantId,
        List<String> tags,
        Instant createdAt,
        Instant updatedAt) {

    public PromptTemplate {
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
        if (updatedAt == null) updatedAt = createdAt;
    }

    public static PromptTemplate create(
            String name, String description, long ownerId, String tenantId, List<String> tags) {
        return new PromptTemplate(0L, name, description, ownerId, tenantId, tags, null, null);
    }

    public PromptTemplate withUpdatedAt(Instant ts) {
        return new PromptTemplate(id, name, description, ownerId, tenantId, tags, createdAt, ts);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("description", description == null ? "" : description);
        m.put("ownerId", ownerId);
        m.put("tenantId", tenantId);
        m.put("tags", tags);
        m.put("createdAt", createdAt.toString());
        m.put("updatedAt", updatedAt.toString());
        return m;
    }
}
