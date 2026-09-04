package com.company.agentgateway.application.prompt;

import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.prompt.PromptExperiment;
import com.company.agentgateway.domain.prompt.PromptVariant;
import com.company.agentgateway.domain.prompt.PromptVersion;
import com.company.agentgateway.domain.prompt.PromptVersionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A/B 实验用例层（spec 2026-09-02 §prompt-version §4.3）。
 *
 * <p>核心能力：
 * <ul>
 *   <li>{@link #assign(long, String)} —— 根据 variant 权重随机分配一个 PromptVersion</li>
 *   <li>{@link #recordResult(long, long, boolean)} —— 记录实验结果(用于后续分析)</li>
 *   <li>{@link #summary(long)} —— 查看实验结果统计</li>
 * </ul>
 */
public class ABTestService {

    private static final Logger log = LoggerFactory.getLogger(ABTestService.class);

    private final PromptVersionRepository versionRepo;
    private final ConcurrentMap<Long, ExperimentStats> stats = new ConcurrentHashMap<>();
    private final AtomicLong nextExperimentId = new AtomicLong(1);

    public ABTestService(PromptVersionRepository versionRepo) {
        this.versionRepo = versionRepo;
    }

    /**
     * 创建实验。{@code variants} 权重累加必须 == 100（由 {@link PromptExperiment} 构造校验）。
     */
    public PromptExperiment createExperiment(long templateId, String name, List<PromptVariant> variants,
                                             String tenantId, long createdBy, AdminRole callerRole) {
        requireOperator(callerRole);
        PromptExperiment exp = new PromptExperiment(
                nextExperimentId.getAndIncrement(),
                templateId, name,
                PromptExperiment.Status.DRAFT,
                variants, tenantId, createdBy, null);
        stats.put(exp.id(), new ExperimentStats());
        log.info("prompt.experiment.created id={} templateId={} variants={} by={}",
                exp.id(), templateId, variants.size(), callerRole);
        return exp;
    }

    /**
     * 给定 experimentId + callerKey(用户/会话稳定字符串),根据权重分配一个 PromptVersion。
     * 使用 callerKey 的 hashCode 做稳定哈希,保证同一用户始终落到同一 variant（实验一致性）。
     */
    public Optional<PromptVersion> assign(long experimentId, String callerKey,
                                          List<PromptVariant> variants) {
        if (variants.isEmpty()) return Optional.empty();
        int hash = Math.abs(callerKey.hashCode() % 100);
        int cumulative = 0;
        for (PromptVariant v : variants) {
            cumulative += v.weight();
            if (hash < cumulative) {
                return versionRepo.findById(v.versionId());
            }
        }
        // 兜底:返回最后一个(若权重和 != 100)
        return versionRepo.findById(variants.get(variants.size() - 1).versionId());
    }

    /** 记录一条结果(versionId 表示用户实际收到的 variant)。 */
    public void recordResult(long experimentId, long versionId, boolean success) {
        ExperimentStats s = stats.computeIfAbsent(experimentId, k -> new ExperimentStats());
        s.total.incrementAndGet();
        if (success) s.success.incrementAndGet();
        s.byVariant.computeIfAbsent(versionId, k -> new VariantStats()).total.incrementAndGet();
        if (success) s.byVariant.get(versionId).success.incrementAndGet();
    }

    /** 汇总统计。 */
    public ExperimentSummary summary(long experimentId) {
        ExperimentStats s = stats.getOrDefault(experimentId, new ExperimentStats());
        long total = s.total.get();
        long success = s.success.get();
        double rate = total == 0 ? 0 : (double) success / total;
        List<VariantSummary> variants = s.byVariant.entrySet().stream()
                .map(e -> new VariantSummary(e.getKey(), e.getValue().total.get(),
                        e.getValue().success.get(), 0.0))
                .toList();
        return new ExperimentSummary(experimentId, total, success, rate, variants);
    }

    private static void requireOperator(AdminRole caller) {
        if (caller == null || !caller.atLeast(AdminRole.OPERATOR)) {
            throw new SecurityException("caller role " + caller + " insufficient (need OPERATOR)");
        }
    }

    private static class ExperimentStats {
        final AtomicLong total = new AtomicLong();
        final AtomicLong success = new AtomicLong();
        final ConcurrentMap<Long, VariantStats> byVariant = new ConcurrentHashMap<>();
    }

    private static class VariantStats {
        final AtomicLong total = new AtomicLong();
        final AtomicLong success = new AtomicLong();
    }

    public record ExperimentSummary(long experimentId, long total, long success,
                                    double successRate,
                                    List<VariantSummary> byVariant) {}

    public record VariantSummary(long versionId, long total, long success, double successRate) {
        public VariantSummary {
            successRate = total == 0 ? 0 : (double) success / total;
        }
    }
}
