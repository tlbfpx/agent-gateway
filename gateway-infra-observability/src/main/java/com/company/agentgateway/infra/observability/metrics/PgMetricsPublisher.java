package com.company.agentgateway.infra.observability.metrics;

import com.company.agentgateway.domain.observability.MetricPoint;
import com.company.agentgateway.infra.persistence.observability.MetricsWriter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Micrometer 指标 → PG metrics_samples 定时发布器(spec 2026-08-19 §4.2/§5.2)。
 *
 * <p>每 30s 快照 MeterRegistry:
 * <ul>
 *   <li>Counter → delta(本次累计值 - 上次累计值;首次跳过建立基线)</li>
 *   <li>Timer → count delta + 总耗时 delta(毫秒,用于均值近似);max/mean 由查询侧从 rollup 取</li>
 *   <li>Gauge → 瞬时值</li>
 * </ul>
 *
 * <p>基数控制(§4.2):指标名白名单 + series(tag 组合)上限 512,超限丢弃新 series。
 */
public class PgMetricsPublisher {

    private static final Logger log = Logger.getLogger(PgMetricsPublisher.class.getName());

    /** 白名单前缀(§4.2:仅预注册指标体系) + 熔断指标(spec B §4.2) + 工作流指标(C1 §6)。 */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "chat.", "agent.", "llm.tokens.", "gateway.errors", "cost.",
            "resilience4j_circuitbreaker.", "workflow.");

    private static final int MAX_SERIES = 512;

    private final MeterRegistry registry;
    private final MetricsWriter writer;
    private final Map<String, Double> lastCumulative = new ConcurrentHashMap<>();
    private final Map<String, Boolean> knownSeries = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;

    public PgMetricsPublisher(MeterRegistry registry, MetricsWriter writer, int intervalSeconds) {
        this.registry = registry;
        this.writer = writer;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pg-metrics-publisher");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleWithFixedDelay(this::snapshot,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    /** 一次快照:遍历 registry → delta 计算 → 批量写入。 */
    void snapshot() {
        try {
            List<MetricPoint> points = new ArrayList<>();
            Instant now = Instant.now();
            for (Meter meter : registry.getMeters()) {
                String name = meter.getId().getName();
                if (!allowed(name)) continue;
                String seriesKey = seriesKey(meter);
                if (!claimSeries(seriesKey)) continue;

                Map<String, String> tags = new HashMap<>();
                meter.getId().getTags().forEach(t -> tags.put(t.getKey(), t.getValue()));
                // rollup 固定维度对齐(spec §4.2):tenant → tenant_id,agent → agent_name
                normalize(tags);

                if (meter instanceof Timer timer) {
                    double countDelta = delta(seriesKey + ":count", timer.count());
                    double totalMsDelta = delta(seriesKey + ":total", timer.totalTime(TimeUnit.MILLISECONDS));
                    if (countDelta > 0) {
                        points.add(new MetricPoint(name + ".count", tags, now, countDelta));
                        points.add(new MetricPoint(name + ".total_ms", tags, now, totalMsDelta));
                    }
                } else if (meter instanceof io.micrometer.core.instrument.Counter counter) {
                    double d = delta(seriesKey, counter.count());
                    if (d > 0) points.add(new MetricPoint(name, tags, now, d));
                } else if (meter instanceof io.micrometer.core.instrument.Gauge gauge) {
                    Double v = gauge.value();
                    if (v != null && !v.isNaN()) points.add(new MetricPoint(name, tags, now, v));
                } else if (meter instanceof io.micrometer.core.instrument.DistributionSummary summary) {
                    double d = delta(seriesKey, summary.count());
                    if (d > 0) {
                        points.add(new MetricPoint(name + ".count", tags, now, d));
                        points.add(new MetricPoint(name + ".total", tags, now,
                                delta(seriesKey + ":total", summary.totalAmount())));
                    }
                }
            }
            if (!points.isEmpty()) writer.batchInsert(points);
        } catch (Exception e) {
            log.log(Level.WARNING, "指标快照失败(本轮跳过): {0}", e.getMessage());
        }
    }

    /** 累计值差分;首次见到该 series 记基线返回 0(不产生假增量)。 */
    private double delta(String key, double current) {
        Double last = lastCumulative.put(key, current);
        if (last == null) return 0;
        double d = current - last;
        return d < 0 ? 0 : d;  // 计数器重置(重启) → 0
    }

    private boolean allowed(String name) {
        return ALLOWED_PREFIXES.stream().anyMatch(name::startsWith);
    }

    /** series 上限控制(§4.2:超限丢弃 + 日志)。 */
    private boolean claimSeries(String key) {
        if (knownSeries.containsKey(key)) return true;
        if (knownSeries.size() >= MAX_SERIES) {
            log.fine(() -> "指标 series 超上限,丢弃: " + key);
            return false;
        }
        knownSeries.put(key, Boolean.TRUE);
        return true;
    }

    private static String seriesKey(Meter meter) {
        StringBuilder sb = new StringBuilder(meter.getId().getName());
        meter.getId().getTags().forEach(t -> sb.append('|').append(t.getKey()).append('=').append(t.getValue()));
        return sb.toString();
    }

    /** tag 名对齐 rollup 固定维度列。 */
    private static void normalize(Map<String, String> tags) {
        String tenant = tags.remove("tenant");
        if (tenant != null) tags.put("tenant_id", tenant);
        String agent = tags.remove("agent");
        if (agent != null) tags.put("agent_name", agent);
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}
