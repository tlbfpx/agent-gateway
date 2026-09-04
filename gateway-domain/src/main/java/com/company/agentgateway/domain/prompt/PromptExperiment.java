package com.company.agentgateway.domain.prompt;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A/B 实验（spec 2026-09-02 §prompt-version §4.2）。
 *
 * <p>绑定到一个 {@link PromptTemplate};持有多个 {@link PromptVariant}。
 * 状态机：{@code DRAFT → RUNNING → COMPLETED}（单向）。
 */
public record PromptExperiment(
        long id,
        long templateId,
        String name,
        Status status,
        List<PromptVariant> variants,
        String tenantId,
        long createdBy,
        Instant createdAt) {

    public enum Status { DRAFT, RUNNING, COMPLETED }

    public PromptExperiment {
        if (templateId <= 0) {
            throw new IllegalArgumentException("templateId must be > 0");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (status == null) status = Status.DRAFT;
        if (variants == null || variants.isEmpty()) {
            throw new IllegalArgumentException("variants must not be empty");
        }
        int total = variants.stream().mapToInt(PromptVariant::weight).sum();
        if (total != 100) {
            throw new IllegalArgumentException("variant weights must sum to 100, got " + total);
        }
        variants = List.copyOf(variants);
        if (createdAt == null) createdAt = Instant.now();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("templateId", templateId);
        m.put("name", name);
        m.put("status", status.name());
        m.put("variants", variants.stream().map(PromptVariant::toMap).toList());
        m.put("tenantId", tenantId);
        m.put("createdBy", createdBy);
        m.put("createdAt", createdAt.toString());
        return m;
    }
}
