package com.company.agentgateway.application.feedback;

import com.company.agentgateway.domain.feedback.FeedbackRecord;
import com.company.agentgateway.domain.feedback.FeedbackRepository;
import com.company.agentgateway.domain.feedback.FeedbackRepository.FeedbackQuery;
import com.company.agentgateway.domain.feedback.FeedbackRepository.Summary;
import com.company.agentgateway.domain.feedback.FeedbackSentiment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Feedback 标注用例层（spec 2026-09-01 §feedback-annotation §4）。
 *
 * <p>三个用例：
 * <ul>
 *   <li>{@link #record} — 落库一条反馈,返回持久化后的 record(id 由 repo 分配)</li>
 *   <li>{@link #query} / {@link #findByTraceId} — 查询</li>
 *   <li>{@link #summarize} — 运营仪表盘聚合</li>
 * </ul>
 *
 * <p>依赖 {@link FeedbackRepository} Port（P0: InMemory, P1: Pg）。
 * 不直接依赖 infra；boot 层通过 Spring 注入。
 */
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    private final FeedbackRepository repository;

    public FeedbackService(FeedbackRepository repository) {
        this.repository = repository;
    }

    /**
     * 落库一条反馈。
     *
     * @param tenantId  租户（必填）
     * @param traceId   关联 trace（必填）
     * @param spanId    可选
     * @param userId    可选
     * @param model     被评价模型（可空）
     * @param sentiment 情感（必填）
     * @param score     1-5 细粒度分（可空）
     * @param comment   备注（自动截断 500 字符）
     * @param tags      自定义标签
     * @param metadata  客户端扩展字段
     * @return 持久化后的 record（含分配的 id）
     */
    public FeedbackRecord record(
            String tenantId, String traceId, String spanId, String userId, String model,
            FeedbackSentiment sentiment, Integer score, String comment,
            List<String> tags, Map<String, Object> metadata) {
        FeedbackRecord incoming = FeedbackRecord.create(
                traceId, spanId, tenantId, userId, model, sentiment, score,
                comment, tags, metadata);
        long id = repository.save(incoming);
        FeedbackRecord persisted = repository.findById(id).orElseThrow(() ->
                new IllegalStateException("feedback persisted but missing: id=" + id));
        log.info("feedback.recorded id={} traceId={} sentiment={} model={}",
                persisted.id(), persisted.traceId(), persisted.sentiment(), persisted.model());
        return persisted;
    }

    /** 多条件查询。 */
    public List<FeedbackRecord> query(FeedbackQuery q) {
        return repository.query(q);
    }

    /** 某次 trace 的全部反馈。 */
    public List<FeedbackRecord> findByTraceId(String traceId) {
        return repository.findByTraceId(traceId);
    }

    /** 聚合统计（运营仪表盘）。 */
    public Summary summarize(FeedbackQuery q) {
        return repository.aggregate(q);
    }
}
