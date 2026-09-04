package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.observability.MetricQueryRepository;
import com.company.agentgateway.domain.observability.MetricQueryRepository.MetricBucket;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 指标趋势查询端点(Dashboard 趋势图数据源,spec 2026-08-19 §6.2)。
 *
 * <p>按 metric + range + bucket 返回分桶 sum 序列;空桶由前端补零。
 * 未配置持久化存储时 503 引导(与 traces/alerts 一致)。
 */
@RestController
@RequestMapping("/v1/admin/metrics/series")
public class AdminMetricsSeriesController {

    private final MetricQueryRepository metrics;

    public AdminMetricsSeriesController(@Autowired(required = false) MetricQueryRepository metrics) {
        this.metrics = metrics;
    }

    /** 常用趋势指标名(前端下拉/校验用)。 */
    private static final java.util.Set<String> ALLOWED = java.util.Set.of(
            "chat.requests", "chat.errors", "chat.latency.count", "chat.latency.total_ms",
            "agent.invocations", "agent.errors", "gateway.errors",
            "llm.tokens.in", "llm.tokens.out");

    @GetMapping
    public Map<String, Object> series(
            @RequestParam String metric,
            @RequestParam(defaultValue = "7d") String range,
            @RequestParam(required = false) Integer bucketSeconds) {
        requireStorage();
        if (!ALLOWED.contains(metric)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metric not allowed: " + metric);
        }
        Duration r = parseRange(range);
        int bucket = bucketSeconds != null && bucketSeconds > 0
                ? bucketSeconds : defaultBucket(r);
        Instant to = Instant.now();
        Instant from = to.minus(r);
        List<MetricBucket> buckets = metrics.queryBuckets(metric, Map.of(), from, to, bucket);
        return Map.of(
                "metric", metric,
                "from", from.toString(),
                "to", to.toString(),
                "bucketSeconds", bucket,
                "points", buckets);
    }

    private static int defaultBucket(Duration range) {
        long hours = range.toHours();
        if (hours <= 1) return 60;        // 1h → 1 分钟桶
        if (hours <= 6) return 300;       // 6h → 5 分钟桶
        if (hours <= 24) return 1800;     // 24h → 30 分钟桶
        return 3600;                      // 7d+ → 1 小时桶
    }

    private void requireStorage() {
        if (metrics == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "未配置持久化存储:请配置 observability.storage.jdbc-url 并启动 docker-compose.observability.yml");
        }
    }

    private static Duration parseRange(String range) {
        if (range.endsWith("m")) return Duration.ofMinutes(Math.max(1, Long.parseLong(range.substring(0, range.length() - 1))));
        if (range.endsWith("h")) return Duration.ofHours(Math.max(1, Long.parseLong(range.substring(0, range.length() - 1))));
        if (range.endsWith("d")) return Duration.ofDays(Math.max(1, Long.parseLong(range.substring(0, range.length() - 1))));
        return Duration.ofHours(24);
    }
}
