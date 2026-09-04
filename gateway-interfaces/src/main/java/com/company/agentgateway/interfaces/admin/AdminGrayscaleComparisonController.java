package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.model.ModelDef;
import com.company.agentgateway.domain.observability.MetricQueryRepository;
import com.company.agentgateway.domain.observability.MetricPoint;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.infra.llm.model.JsonFileModelRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 灰度分组效果对比（spec §5.5 二期）：按灰度组内各成员模型聚合近期效果指标。
 *
 * <p>GET /v1/admin/models/{modelId}/grayscale-comparison?range=24h
 *
 * <p>灰度组定义与 {@link com.company.agentgateway.infra.llm.port.WeightedModelSelector}
 * 一致：同 displayName 的全部模型（weight 加权分流）。
 *
 * <p>数据源优先级：
 * <ol>
 *   <li>MetricQueryRepository（PG/TimescaleDB metrics_samples，tags.model = 成员模型 id）</li>
 *   <li>MeterRegistry（内存降级：进程内 Micrometer 累计值，未接 PG 时可用）</li>
 *   <li>两者皆无 → 仅返回权重/成员结构，指标为 0（source=none）</li>
 * </ol>
 * 成本按 llm.tokens.in/out × ModelDef.costPer1k{In,Out} 估算（CNY）。
 *
 * <p>注意：指标 tags.model 记录请求/命中模型 id；灰度命中成员若与请求 id 不同，
 * 需上报侧带成员 id 才会点亮对应行（ChatOrchestrator 埋点由协同任务扩展）。
 */
@RestController
@RequestMapping("/v1/admin/models")
public class AdminGrayscaleComparisonController {

    private final JsonFileModelRegistry registry;
    private final MetricQueryRepository metrics;   // nullable
    private final MeterRegistry meterRegistry;     // nullable

    @Autowired
    public AdminGrayscaleComparisonController(JsonFileModelRegistry registry,
                                              @Autowired(required = false) MetricQueryRepository metrics,
                                              @Autowired(required = false) MeterRegistry meterRegistry) {
        this.registry = registry;
        this.metrics = metrics;
        this.meterRegistry = meterRegistry;
    }

    @GetMapping("/{modelId}/grayscale-comparison")
    public Map<String, Object> comparison(@PathVariable String modelId,
                                          @RequestParam(defaultValue = "24h") String range) {
        ModelDef target = registry.getModel(new ModelId(modelId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Model not found: " + modelId));

        // 灰度组：同 displayName（与 WeightedModelSelector 组定义一致），含停用成员以便对照
        List<ModelDef> group = registry.listModels().stream()
                .filter(m -> m.displayName() != null && m.displayName().equals(target.displayName()))
                .sorted(Comparator.comparing((ModelDef m) -> m.normalizedWeight()).reversed()
                        .thenComparing(m -> m.id().value()))
                .toList();

        Duration window = parseRange(range);
        Instant to = Instant.now();
        Instant from = to.minus(window);
        String source = metrics != null ? "metrics-store" : (meterRegistry != null ? "memory" : "none");

        List<Map<String, Object>> members = new ArrayList<>();
        for (ModelDef m : group) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("modelId", m.id().value());
            row.put("displayName", m.displayName());
            row.put("provider", m.provider());
            row.put("weight", m.normalizedWeight());
            row.put("enabled", m.enabled());
            Stats s = metrics != null ? fromMetricsStore(m, from, to)
                    : (meterRegistry != null ? fromMemory(m) : new Stats());
            row.put("requests", s.requests);
            row.put("errors", s.errors);
            row.put("errorRate", s.requests > 0 ? round(s.errors / s.requests) : 0.0);
            row.put("p50LatencyMs", round(s.p50Ms));
            row.put("p95LatencyMs", round(s.p95Ms));
            row.put("tokensIn", s.tokensIn);
            row.put("tokensOut", s.tokensOut);
            row.put("costCny", cost(m, s.tokensIn, s.tokensOut));
            members.add(row);
        }

        return Map.of(
                "modelId", modelId,
                "group", target.displayName() == null ? "" : target.displayName(),
                "from", from.toString(),
                "to", to.toString(),
                "source", source,
                "members", members);
    }

    // ---------- PG 指标存储路径 ----------

    private Stats fromMetricsStore(ModelDef m, Instant from, Instant to) {
        Map<String, String> tags = Map.of("model", m.id().value());
        Stats s = new Stats();
        s.requests = sum("chat.requests", tags, from, to);
        s.errors = sum("chat.errors", tags, from, to);
        s.tokensIn = (long) sum("llm.tokens.in", tags, from, to);
        s.tokensOut = (long) sum("llm.tokens.out", tags, from, to);
        // 延迟：total_ms 与 count 按时间戳配对 → 每点均值 → 分位数近似
        Map<Instant, double[]> lat = new TreeMap<>();
        for (MetricPoint p : metrics.querySeries("chat.latency.total_ms", tags, from, to)) {
            lat.computeIfAbsent(p.ts(), k -> new double[2])[0] = p.value();
        }
        for (MetricPoint p : metrics.querySeries("chat.latency.count", tags, from, to)) {
            double[] slot = lat.computeIfAbsent(p.ts(), k -> new double[2]);
            slot[1] = p.value();
        }
        List<Double> avgs = lat.values().stream()
                .filter(v -> v[1] > 0)
                .map(v -> v[0] / v[1])
                .sorted()
                .toList();
        s.p50Ms = percentile(avgs, 0.50);
        s.p95Ms = percentile(avgs, 0.95);
        return s;
    }

    private double sum(String metric, Map<String, String> tags, Instant from, Instant to) {
        return metrics.windowSum(metric, tags, from, to).orElse(0.0);
    }

    private static double percentile(List<Double> sorted, double q) {
        if (sorted.isEmpty()) return 0.0;
        int idx = (int) Math.min(sorted.size() - 1, Math.ceil(q * (sorted.size() - 1)));
        return sorted.get(idx);
    }

    // ---------- 内存降级路径（MeterRegistry 累计值） ----------

    private Stats fromMemory(ModelDef m) {
        Stats s = new Stats();
        String id = m.id().value();
        s.requests = counter("chat.requests", id);
        s.errors = counter("chat.errors", id);
        s.tokensIn = (long) counter("llm.tokens.in", id);
        s.tokensOut = (long) counter("llm.tokens.out", id);
        Timer timer = meterRegistry.find("chat.latency").tags("model", id).timer();
        if (timer != null && timer.count() > 0) {
            // 无 histogram 时无精确分位数：p50≈mean，p95≈max（近似口径，见类注释）
            s.p50Ms = timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS);
            s.p95Ms = Math.max(s.p50Ms, timer.max(java.util.concurrent.TimeUnit.MILLISECONDS));
        }
        return s;
    }

    private double counter(String name, String model) {
        var c = meterRegistry.find(name).tags("model", model).counter();
        return c == null ? 0.0 : c.count();
    }

    // ---------- 工具 ----------

    private static BigDecimal cost(ModelDef m, long tokensIn, long tokensOut) {
        BigDecimal in = m.costPer1kIn() == null ? BigDecimal.ZERO : m.costPer1kIn();
        BigDecimal out = m.costPer1kOut() == null ? BigDecimal.ZERO : m.costPer1kOut();
        return BigDecimal.valueOf(tokensIn).multiply(in)
                .add(BigDecimal.valueOf(tokensOut).multiply(out))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    private static Duration parseRange(String range) {
        if (range == null || range.isBlank()) return Duration.ofHours(24);
        try {
            if (range.endsWith("m")) return Duration.ofMinutes(Math.max(1, Long.parseLong(range.substring(0, range.length() - 1))));
            if (range.endsWith("h")) return Duration.ofHours(Math.max(1, Long.parseLong(range.substring(0, range.length() - 1))));
            if (range.endsWith("d")) return Duration.ofDays(Math.max(1, Long.parseLong(range.substring(0, range.length() - 1))));
        } catch (NumberFormatException ignore) {
            // fall through
        }
        return Duration.ofHours(24);
    }

    private static final class Stats {
        double requests;
        double errors;
        double p50Ms;
        double p95Ms;
        long tokensIn;
        long tokensOut;
    }
}
