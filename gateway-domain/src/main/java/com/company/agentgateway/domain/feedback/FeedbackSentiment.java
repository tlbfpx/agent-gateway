package com.company.agentgateway.domain.feedback;

/**
 * 用户反馈情感分类（spec 2026-09-01 §feedback-annotation）。
 *
 * <p>由用户在 Chat 页 / Trace 详情 / SDK 调 {@code POST /v1/feedback} 时选择：
 * <ul>
 *   <li>{@link #POSITIVE} 👍 —— 对回答满意</li>
 *   <li>{@link #NEGATIVE} 👎 —— 对回答不满意</li>
 *   <li>{@link #NEUTRAL} —— 中性(已阅不评价,留作 future SDK 调)</li>
 * </ul>
 *
 * <p>与 {@link FeedbackRecord#score()} (1–5) 互补：sentiment 是粗分类,score 是细粒度。
 */
public enum FeedbackSentiment {
    POSITIVE,
    NEGATIVE,
    NEUTRAL;

    /** 兼容字符串解析(API 接受 {@code "positive"/"POSITIVE"/"thumbs_up"/"👍"} 等)。 */
    public static FeedbackSentiment parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("sentiment must not be blank");
        }
        String normalized = raw.trim().toLowerCase();
        return switch (normalized) {
            case "positive", "thumbs_up", "thumbsup", "up", "like", "👍", "good" -> POSITIVE;
            case "negative", "thumbs_down", "thumbsdown", "down", "dislike", "👎", "bad" -> NEGATIVE;
            case "neutral", "ok", "fine", "neutral." -> NEUTRAL;
            default -> {
                try {
                    yield FeedbackSentiment.valueOf(normalized.toUpperCase());
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException(
                            "unknown sentiment: " + raw + " (use positive|negative|neutral)");
                }
            }
        };
    }
}
