package com.company.agentgateway.domain.config;

import java.time.Instant;
import java.util.Map;

/**
 * 配置变更事件(Sprint 1 P0 §3.4)。
 *
 * <p>字段语义:
 * <ul>
 *   <li>{@code name}:配置域(如 "models"、"api-keys"、"webhook"、"rbac"、"mcp-servers"、"rate-limit")</li>
 *   <li>{@code source}:变更来源(file / nacos / k8s-configmap / rest)</li>
 *   <li>{@code version}:版本号;file/nacos 场景使用 epochMs,rest 场景使用递增计数</li>
 *   <li>{@code payload}:已解析的配置内容(可选);对于 file source 通常为解析后的 List/Map</li>
 *   <li>{@code summary}:变更摘要(可选,如 diff 行数、字段列表);用于审计/UI 提示</li>
 *   <li>{@code actor}:操作者(用户 id / "system");nacos/k8s 来源为 "system"</li>
 *   <li>{@code occurredAt}:事件发布时间</li>
 * </ul>
 */
public record ConfigChanged(
        String name,
        Source source,
        long version,
        Object payload,
        Map<String, Object> summary,
        String actor,
        Instant occurredAt
) {
    public enum Source { FILE, NACOS, K8S_CONFIGMAP, REST, API }

    public ConfigChanged {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (source == null) source = Source.FILE;
        if (occurredAt == null) occurredAt = Instant.now();
    }

    /** 便捷构造:无 payload / 无 summary。 */
    public static ConfigChanged of(String name, Source source, long version, String actor) {
        return new ConfigChanged(name, source, version, null, null, actor, Instant.now());
    }
}