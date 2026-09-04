package com.company.agentgateway.domain.feedback;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Feedback 标注持久化端口（spec 2026-09-01 §feedback-annotation §3.2）。
 *
 * <p>domain 层抽象存储与查询；实现：
 * <ul>
 *   <li>P0：{@code InMemoryFeedbackRepository}（gateway-infra-persistence Round 11）</li>
 *   <li>P1：{@code PgFeedbackRepository}（Round 12 jdbcTemplate + pg 表）</li>
 * </ul>
 */
public interface FeedbackRepository {

    /** 写入一条反馈；返回新 ID。 */
    long save(FeedbackRecord record);

    /** 按主键查。 */
    Optional<FeedbackRecord> findById(long id);

    /** 按 traceId 查全部反馈（某次调用收到的所有标注）。 */
    List<FeedbackRecord> findByTraceId(String traceId);

    /**
     * 条件查询（管理端）。
     *
     * @param query 查询参数；所有可空字段都为 null 时返回最近 limit 条
     * @return 按 createdAt desc 排序的反馈列表
     */
    List<FeedbackRecord> query(FeedbackQuery query);

    /** 删一条（管理端 / 用户撤回）。返回受影响行数。 */
    int deleteById(long id);

    /** 全表统计（管理仪表盘）。 */
    Summary aggregate(FeedbackQuery query);

    /** 查询参数封装（record 默认全字段可空）。 */
    record FeedbackQuery(
            String tenantId,
            String traceId,
            String userId,
            String model,
            FeedbackSentiment sentiment,
            Instant from,
            Instant to,
            int limit,
            int offset) {
        public FeedbackQuery {
            limit = limit <= 0 ? 50 : Math.min(limit, 500);
            offset = Math.max(offset, 0);
        }
    }

    /** 聚合统计结果。 */
    record Summary(
            long total,
            long positive,
            long negative,
            long neutral,
            double positiveRatio,
            long withComment,
            List<ModelBucket> byModel,
            List<TagCount> topTags) {

        public static Summary empty() {
            return new Summary(0, 0, 0, 0, 0.0, 0, List.of(), List.of());
        }

        public record ModelBucket(String model, long count, long positive, long negative) {}

        public record TagCount(String tag, long count) {}
    }
}
