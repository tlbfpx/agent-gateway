package com.company.agentgateway.infra.persistence.feedback;

import com.company.agentgateway.domain.feedback.FeedbackRecord;
import com.company.agentgateway.domain.feedback.FeedbackRepository;
import com.company.agentgateway.domain.feedback.FeedbackSentiment;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FeedbackRepository 内存实现（spec 2026-09-01 §feedback-annotation §3.2 P0）。
 *
 * <p>进程内 {@code CopyOnWriteArrayList} + 内存过滤；适用单实例 / 演示 / 开发环境。
 * Round 12 替换为 {@code PgFeedbackRepository}。
 */
public class InMemoryFeedbackRepository implements FeedbackRepository {

    private final CopyOnWriteArrayList<FeedbackRecord> records = new CopyOnWriteArrayList<>();
    private final AtomicLong nextId = new AtomicLong(1);

    @Override
    public long save(FeedbackRecord record) {
        long id = nextId.getAndIncrement();
        FeedbackRecord persisted = new FeedbackRecord(
                id, record.traceId(), record.spanId(), record.tenantId(),
                record.userId(), record.model(), record.sentiment(), record.score(),
                record.comment(), record.tags(), record.metadata(), record.createdAt());
        records.add(persisted);
        return id;
    }

    @Override
    public Optional<FeedbackRecord> findById(long id) {
        return records.stream().filter(r -> r.id() == id).findFirst();
    }

    @Override
    public List<FeedbackRecord> findByTraceId(String traceId) {
        return records.stream()
                .filter(r -> traceId.equals(r.traceId()))
                .sorted(Comparator.comparing(FeedbackRecord::createdAt).reversed())
                .toList();
    }

    @Override
    public List<FeedbackRecord> query(FeedbackQuery query) {
        return records.stream()
                .filter(r -> query.tenantId() == null || query.tenantId().equals(r.tenantId()))
                .filter(r -> query.traceId() == null || query.traceId().equals(r.traceId()))
                .filter(r -> query.userId() == null || query.userId().equals(r.userId()))
                .filter(r -> query.model() == null || query.model().equals(r.model()))
                .filter(r -> query.sentiment() == null || r.sentiment() == query.sentiment())
                .filter(r -> query.from() == null || !r.createdAt().isBefore(query.from()))
                .filter(r -> query.to() == null || !r.createdAt().isAfter(query.to()))
                .sorted(Comparator.comparing(FeedbackRecord::createdAt).reversed())
                .skip(query.offset())
                .limit(query.limit())
                .toList();
    }

    @Override
    public int deleteById(long id) {
        int before = records.size();
        records.removeIf(r -> r.id() == id);
        return before - records.size();
    }

    @Override
    public Summary aggregate(FeedbackQuery query) {
        List<FeedbackRecord> filtered = records.stream()
                .filter(r -> query.tenantId() == null || query.tenantId().equals(r.tenantId()))
                .filter(r -> query.model() == null || query.model().equals(r.model()))
                .filter(r -> query.from() == null || !r.createdAt().isBefore(query.from()))
                .filter(r -> query.to() == null || !r.createdAt().isAfter(query.to()))
                .toList();

        long total = filtered.size();
        long positive = filtered.stream().filter(r -> r.sentiment() == FeedbackSentiment.POSITIVE).count();
        long negative = filtered.stream().filter(r -> r.sentiment() == FeedbackSentiment.NEGATIVE).count();
        long neutral = filtered.stream().filter(r -> r.sentiment() == FeedbackSentiment.NEUTRAL).count();
        long withComment = filtered.stream().filter(r -> r.comment() != null && !r.comment().isBlank()).count();
        double positiveRatio = total == 0 ? 0.0 : (double) positive / total;

        Map<String, long[]> byModelMap = new LinkedHashMap<>();
        for (FeedbackRecord r : filtered) {
            String key = r.model() == null ? "(unknown)" : r.model();
            long[] bucket = byModelMap.computeIfAbsent(key, k -> new long[3]);
            bucket[0]++;
            if (r.sentiment() == FeedbackSentiment.POSITIVE) bucket[1]++;
            else if (r.sentiment() == FeedbackSentiment.NEGATIVE) bucket[2]++;
        }
        List<Summary.ModelBucket> byModel = byModelMap.entrySet().stream()
                .map(e -> new Summary.ModelBucket(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                .sorted(Comparator.comparingLong(Summary.ModelBucket::count).reversed())
                .limit(10)
                .toList();

        Map<String, Long> tagCount = new LinkedHashMap<>();
        for (FeedbackRecord r : filtered) {
            for (String tag : r.tags()) {
                tagCount.merge(tag, 1L, Long::sum);
            }
        }
        List<Summary.TagCount> topTags = tagCount.entrySet().stream()
                .map(e -> new Summary.TagCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(Summary.TagCount::count).reversed())
                .limit(10)
                .toList();

        return new Summary(total, positive, negative, neutral, positiveRatio, withComment, byModel, topTags);
    }
}
