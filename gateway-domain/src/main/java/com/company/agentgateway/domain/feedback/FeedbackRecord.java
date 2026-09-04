package com.company.agentgateway.domain.feedback;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户反馈标注记录（spec 2026-09-01 §feedback-annotation §3.1）。
 *
 * <p>由用户在 Chat / Trace 详情 / SDK 上提交，关联到一次 LLM 调用（{@code traceId + spanId}）。
 * 字段语义：
 * <ul>
 *   <li>{@code id} —— 数据库主键；0 表示未持久化</li>
 *   <li>{@code traceId} —— 关联 span/trace（必填）</li>
 *   <li>{@code spanId} —— 可选；如对某个具体消息打标</li>
 *   <li>{@code tenantId} —— 租户隔离</li>
 *   <li>{@code userId} —— 反馈提交者；可空（匿名）</li>
 *   <li>{@code model} —— 被评价的逻辑模型名</li>
 *   <li>{@code sentiment} 👍/👎/中性</li>
 *   <li>{@code score} 1–5 细粒度分（可空）</li>
 *   <li>{@code comment} 备注文本（≤500 字符，超出由 controller 截断）</li>
 *   <li>{@code tags} 自定义标签（例：{@code "hallucination"/"too_slow"/"good_explanation"}）</li>
 *   <li>{@code metadata} 客户端扩展字段（JSON 风格）</li>
 *   <li>{@code createdAt} 服务器时间戳</li>
 * </ul>
 *
 * <p>不可变 record；用 {@link #create()} 构造新记录（id=0, createdAt=now）。
 */
public record FeedbackRecord(
        long id,
        String traceId,
        String spanId,
        String tenantId,
        String userId,
        String model,
        FeedbackSentiment sentiment,
        Integer score,
        String comment,
        List<String> tags,
        Map<String, Object> metadata,
        Instant createdAt) {

    public FeedbackRecord {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (sentiment == null) {
            throw new IllegalArgumentException("sentiment must not be null");
        }
        if (score != null && (score < 1 || score > 5)) {
            throw new IllegalArgumentException("score must be 1..5, got " + score);
        }
        if (comment != null && comment.length() > 500) {
            comment = comment.substring(0, 500);
        }
        tags = tags == null ? List.of() : List.copyOf(tags);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** 构造一条待持久化的反馈记录（id 由 repo 分配，createdAt 取 now）。 */
    public static FeedbackRecord create(
            String traceId, String spanId, String tenantId, String userId,
            String model, FeedbackSentiment sentiment, Integer score,
            String comment, List<String> tags, Map<String, Object> metadata) {
        return new FeedbackRecord(0L, traceId, spanId, tenantId, userId, model,
                sentiment, score, comment, tags, metadata, Instant.now());
    }

    /** 用于管理后台列表展示的扁平视图。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("traceId", traceId);
        m.put("spanId", spanId == null ? "" : spanId);
        m.put("tenantId", tenantId);
        m.put("userId", userId == null ? "" : userId);
        m.put("model", model == null ? "" : model);
        m.put("sentiment", sentiment.name());
        m.put("score", score == null ? 0 : score);
        m.put("comment", comment == null ? "" : comment);
        m.put("tags", tags);
        m.put("metadata", metadata);
        m.put("createdAt", createdAt.toString());
        return m;
    }
}
