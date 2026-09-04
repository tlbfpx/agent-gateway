package com.company.agentgateway.infra.llm.routing;

import com.company.agentgateway.domain.routing.Candidate;
import com.company.agentgateway.domain.routing.RouteDecision;
import com.company.agentgateway.domain.routing.RouteDecision.Source;
import com.company.agentgateway.domain.routing.RoutingContext;
import com.company.agentgateway.domain.routing.RoutingMetricsSnapshot;
import com.company.agentgateway.domain.routing.RoutingPolicy;
import com.company.agentgateway.domain.routing.RoutingPolicyExhaustedException;
import com.company.agentgateway.domain.routing.RoutingPort;
import com.company.agentgateway.domain.routing.RoutingStrategy;
import com.company.agentgateway.domain.shared.ModelId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * 默认路由决策服务(Round 10):实现 RoutingPort,包含 4 种策略算法 + fallback chain 兜底。
 *
 * <h2>决策流程</h2>
 * <ol>
 *   <li>过滤超 budget 候选(costCeiling / latencyP99Ceiling)</li>
 *   <li>按 strategy 选主候选 → RouteDecision</li>
 *   <li>主候选全部失败(空) → 顺序尝试 fallbackChain 中模型(直接 fallback,无历史指标也允许)</li>
 *   <li>fallback 也耗尽 → 抛 RoutingPolicyExhaustedException</li>
 * </ol>
 *
 * <h2>算法要点(GW-RT-004 ~ GW-RT-007)</h2>
 * <ul>
 *   <li>LOWEST_COST:按 avgCostCents 升序;无指标样本 → 按 costCeiling 最小 fallback</li>
 *   <li>FASTEST_FIRST_TOKEN:按 p50LatencyMs 升序;无样本 → 按 latencyP99Ceiling 最小 fallback</li>
 *   <li>QUALITY_FIRST:按 successRate × min(samples/100, 1.0) 降序</li>
 *   <li>WEIGHTED:按 Candidate.weight 比例随机;seed 由 RoutingContext.randomSeed 提供(deterministic)</li>
 * </ul>
 */
public class DefaultRoutingService implements RoutingPort {

    private static final Logger log = LoggerFactory.getLogger(DefaultRoutingService.class);

    @Override
    public RouteDecision decide(RoutingPolicy policy,
                                 List<RoutingMetricsSnapshot> candidateMetrics,
                                 RoutingContext ctx) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(ctx, "ctx");
        // 1. 指标 → modelId 映射
        Map<ModelId, RoutingMetricsSnapshot> snapMap = (candidateMetrics == null ? List.<RoutingMetricsSnapshot>of() : candidateMetrics)
                .stream().collect(Collectors.toMap(RoutingMetricsSnapshot::modelId, s -> s, (a, b) -> a));

        // 2. 过滤超 budget 候选(同时收集 rejected 原因)
        List<RouteDecision.RejectedCandidate> rejected = new ArrayList<>();
        List<Candidate> filtered = new ArrayList<>();
        for (Candidate c : policy.candidates()) {
            String reason = overBudgetReason(c, snapMap.get(c.modelId()));
            if (reason != null) {
                rejected.add(new RouteDecision.RejectedCandidate(c.modelId().value(), reason));
            } else {
                filtered.add(c);
            }
        }

        // 3. 主候选决策
        if (!filtered.isEmpty()) {
            RouteDecision primary = pickPrimary(policy, filtered, snapMap, ctx, rejected);
            if (primary != null) return primary;
        }

        // 4. fallback chain(顺序尝试;无指标要求)
        for (String fb : policy.fallbackChain()) {
            ModelId m = new ModelId(fb);
            // 仅检查 policy 自带的 ceiling(若 fallback 模型注册为 candidate 但已剔除,这里仍允许)
            // 简化:fallback 模型无任何 ceiling 限制,作为兜底
            String rationale = "FALLBACK: chain attempt on " + fb
                    + (filtered.isEmpty() ? " (primary candidates exhausted)" : " (primary selection failed)");
            return new RouteDecision(m, rationale, rejected, Source.FALLBACK);
        }

        // 5. 全部耗尽
        throw new RoutingPolicyExhaustedException(policy.id(),
                "All candidates exhausted (filtered=" + filtered.size()
                        + " of " + policy.candidates().size() + ")"
                        + ", fallbackChain=" + policy.fallbackChain().size());
    }

    /** 返回 null 表示主候选决策失败(走 fallback);否则返回 RouteDecision。 */
    private RouteDecision pickPrimary(RoutingPolicy policy, List<Candidate> filtered,
                                       Map<ModelId, RoutingMetricsSnapshot> snapMap,
                                       RoutingContext ctx,
                                       List<RouteDecision.RejectedCandidate> rejected) {
        return switch (policy.strategy()) {
            case LOWEST_COST -> pickLowestCost(policy, filtered, snapMap, rejected);
            case FASTEST_FIRST_TOKEN -> pickFastest(policy, filtered, snapMap, rejected);
            case QUALITY_FIRST -> pickQuality(policy, filtered, snapMap, rejected);
            case WEIGHTED -> pickWeighted(policy, filtered, ctx, rejected);
        };
    }

    // ================== Strategy algorithms ==================

    private RouteDecision pickLowestCost(RoutingPolicy policy, List<Candidate> candidates,
                                          Map<ModelId, RoutingMetricsSnapshot> snapMap,
                                          List<RouteDecision.RejectedCandidate> rejected) {
        // 优先有指标的:按 avgCostCents 升序;无指标候选 → 按 costCeilingCents 升序兜底
        List<Candidate> withMetrics = new ArrayList<>();
        List<Candidate> cold = new ArrayList<>();
        for (Candidate c : candidates) {
            var snap = snapMap.get(c.modelId());
            if (snap != null && snap.hasSamples() && snap.avgCostCents() != null) {
                withMetrics.add(c);
            } else {
                cold.add(c);
            }
        }
        Candidate chosen;
        String rationaleExtra;
        if (!withMetrics.isEmpty()) {
            chosen = withMetrics.stream()
                    .min(Comparator.comparing(c -> snapMap.get(c.modelId()).avgCostCents()))
                    .orElseThrow();
            BigDecimal cost = snapMap.get(chosen.modelId()).avgCostCents();
            rationaleExtra = " avgCost=" + cost + " cents";
        } else {
            // Cold Start:按 costCeilingCents 升序,无 ceiling 排最后
            chosen = cold.stream()
                    .min(Comparator.comparing(
                            c -> c.costCeilingCents() != null ? c.costCeilingCents() : new BigDecimal(Long.MAX_VALUE)))
                    .orElseThrow();
            rationaleExtra = " (cold start) costCeiling="
                    + chosen.costCeilingCents();
        }
        return new RouteDecision(chosen.modelId(),
                "LOWEST_COST:" + chosen.modelId().value() + rationaleExtra,
                rejected, Source.PRIMARY);
    }

    private RouteDecision pickFastest(RoutingPolicy policy, List<Candidate> candidates,
                                       Map<ModelId, RoutingMetricsSnapshot> snapMap,
                                       List<RouteDecision.RejectedCandidate> rejected) {
        List<Candidate> withMetrics = new ArrayList<>();
        List<Candidate> cold = new ArrayList<>();
        for (Candidate c : candidates) {
            var snap = snapMap.get(c.modelId());
            if (snap != null && snap.hasSamples() && snap.p50LatencyMs() != null) {
                withMetrics.add(c);
            } else {
                cold.add(c);
            }
        }
        Candidate chosen;
        String rationaleExtra;
        if (!withMetrics.isEmpty()) {
            chosen = withMetrics.stream()
                    .min(Comparator.comparingLong(c -> snapMap.get(c.modelId()).p50LatencyMs()))
                    .orElseThrow();
            rationaleExtra = " p50=" + snapMap.get(chosen.modelId()).p50LatencyMs() + "ms";
        } else {
            chosen = cold.stream()
                    .min(Comparator.comparing(
                            c -> c.latencyP99CeilingMs() != null ? c.latencyP99CeilingMs() : Long.MAX_VALUE))
                    .orElseThrow();
            rationaleExtra = " (cold start) latencyP99Ceiling=" + chosen.latencyP99CeilingMs() + "ms";
        }
        return new RouteDecision(chosen.modelId(),
                "FASTEST_FIRST_TOKEN:" + chosen.modelId().value() + rationaleExtra,
                rejected, Source.PRIMARY);
    }

    private RouteDecision pickQuality(RoutingPolicy policy, List<Candidate> candidates,
                                       Map<ModelId, RoutingMetricsSnapshot> snapMap,
                                       List<RouteDecision.RejectedCandidate> rejected) {
        // quality score = successRate × min(samples/100, 1.0);无样本 → 0
        Candidate chosen = candidates.stream()
                .max(Comparator.comparingDouble(c -> qualityScore(snapMap.get(c.modelId()))))
                .orElseThrow();
        var snap = snapMap.get(chosen.modelId());
        double score = qualityScore(snap);
        return new RouteDecision(chosen.modelId(),
                "QUALITY_FIRST:" + chosen.modelId().value()
                        + " score=" + String.format("%.3f", score)
                        + " (successRate=" + (snap != null ? snap.successRate() : 0)
                        + ", samples=" + (snap != null ? snap.sampleCount() : 0) + ")",
                rejected, Source.PRIMARY);
    }

    private RouteDecision pickWeighted(RoutingPolicy policy, List<Candidate> candidates,
                                        RoutingContext ctx,
                                        List<RouteDecision.RejectedCandidate> rejected) {
        long totalWeight = candidates.stream().mapToLong(Candidate::weight).sum();
        if (totalWeight <= 0) return null; // 防御:理论不应发生(weight > 0)
        // 用 ctx.randomSeed 作为种子,确保 deterministic
        // 用 nextLong() 产生稳定长整型,后映射到 weight 桶(避免 nextInt(n) 在某些 seed 范围的偏差)
        Random random = new Random(ctx.randomSeed());
        // nextLong 范围 [-Long.MAX_VALUE, Long.MAX_VALUE);取绝对值后模 totalWeight
        long abs = random.nextLong() == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(random.nextLong());
        long pick = abs % totalWeight;
        long acc = 0;
        for (Candidate c : candidates) {
            acc += c.weight();
            if (pick < acc) {
                return new RouteDecision(c.modelId(),
                        "WEIGHTED:" + c.modelId().value()
                                + " weight=" + c.weight() + "/" + totalWeight
                                + " seed=" + ctx.randomSeed(),
                        rejected, Source.PRIMARY);
            }
        }
        // 浮点边界 → 取最后一个
        Candidate last = candidates.get(candidates.size() - 1);
        return new RouteDecision(last.modelId(),
                "WEIGHTED:" + last.modelId().value() + " weight=" + last.weight() + "/" + totalWeight,
                rejected, Source.PRIMARY);
    }

    /** quality score: successRate × confidenceFactor(confidenceFactor = min(samples/100, 1.0))。 */
    private static double qualityScore(RoutingMetricsSnapshot snap) {
        if (snap == null || !snap.hasSamples()) return 0.0;
        double confidence = Math.min(snap.sampleCount() / 100.0, 1.0);
        return snap.successRate() * confidence;
    }

    /** 检查 candidate 是否超 ceiling;返回超出的具体原因,null = 通过。 */
    private String overBudgetReason(Candidate c, RoutingMetricsSnapshot snap) {
        if (c.costCeilingCents() != null && snap != null && snap.hasSamples()
                && snap.avgCostCents() != null
                && snap.avgCostCents().compareTo(c.costCeilingCents()) > 0) {
            return "avgCost " + snap.avgCostCents() + " > ceiling " + c.costCeilingCents();
        }
        if (c.latencyP99CeilingMs() != null && snap != null && snap.hasSamples()
                && snap.p50LatencyMs() != null
                && snap.p50LatencyMs() > c.latencyP99CeilingMs()) {
            return "p50 " + snap.p50LatencyMs() + "ms > p99 ceiling " + c.latencyP99CeilingMs() + "ms";
        }
        return null;
    }
}