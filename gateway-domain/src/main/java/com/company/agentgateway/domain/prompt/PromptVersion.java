package com.company.agentgateway.domain.prompt;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prompt 模板的某个版本（spec 2026-09-02 §prompt-version §3.2）。
 *
 * <p>{@code version} 由 1 开始单调递增;同 templateId 下唯一。
 * {@code params} 是模型参数 JSON（temperature/topP/maxTokens 等）,
 * 保留为 Map 让前端/SDK 灵活扩展。
 */
public record PromptVersion(
        long id,
        long templateId,
        int version,
        String systemPrompt,
        String userPrompt,
        String model,
        Map<String, Object> params,
        long authorId,
        Instant createdAt) {

    public PromptVersion {
        if (templateId <= 0) {
            throw new IllegalArgumentException("templateId must be > 0, got " + templateId);
        }
        if (version <= 0) {
            throw new IllegalArgumentException("version must be > 0, got " + version);
        }
        if (authorId <= 0) {
            throw new IllegalArgumentException("authorId must be > 0, got " + authorId);
        }
        if (systemPrompt == null && userPrompt == null) {
            throw new IllegalArgumentException("systemPrompt or userPrompt required");
        }
        if (params == null) params = Map.of();
        else params = Map.copyOf(params);
        if (createdAt == null) createdAt = Instant.now();
    }

    public static PromptVersion create(
            long templateId, int version, String systemPrompt, String userPrompt,
            String model, Map<String, Object> params, long authorId) {
        return new PromptVersion(0L, templateId, version, systemPrompt, userPrompt,
                model, params, authorId, null);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("templateId", templateId);
        m.put("version", version);
        m.put("systemPrompt", systemPrompt == null ? "" : systemPrompt);
        m.put("userPrompt", userPrompt == null ? "" : userPrompt);
        m.put("model", model == null ? "" : model);
        m.put("params", params);
        m.put("authorId", authorId);
        m.put("createdAt", createdAt.toString());
        return m;
    }
}
