package com.company.agentgateway.domain.workflow;

import java.time.Instant;

/**
 * Workflow 定义(spec C1 §8 扩展):可复用 workflow definition,
 * name 唯一,body 是 JSON 或 YAML 文本(POST /v1/workflows/run 时按 format 解析)。
 */
public record WorkflowDefinition(
        String name,
        String description,
        String body,
        Format format,
        Instant createdAt,
        Instant updatedAt,
        String createdBy) {

    public enum Format { JSON, YAML;
        public static Format from(String s) {
            if (s == null) return JSON;
            return s.equalsIgnoreCase("yaml") || s.equalsIgnoreCase("yml") ? YAML : JSON;
        }
    }
}