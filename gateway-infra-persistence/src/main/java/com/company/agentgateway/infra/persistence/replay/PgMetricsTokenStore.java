package com.company.agentgateway.infra.persistence.replay;

import com.company.agentgateway.domain.replay.MetricsQueryPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PG 实现 {@link MetricsQueryPort}(Sprint 2 P3.3 + P4.3):
 * 从 {@code metrics_samples} 表按 trace_id 聚合 llm_tokens_in / llm_tokens_out。
 *
 * <p><b>约定</b>:调用方写入 metrics_samples 时,tags JSONB 必须含 {@code trace_id} 字段:
 * <pre>
 * tags: {"trace_id":"trace-xxx","tenant":"t1","model":"gpt-4o"}
 * value: token count(int)
 * metric_name: "llm_tokens_in" 或 "llm_tokens_out"
 * </pre>
 *
 * <h2>P4.3 容错 + 指标</h2>
 * <ul>
 *   <li>trace_id tag 缺失 → 不抛错,返回 {@code Optional.empty()} + 累加 tag_missing counter</li>
 *   <li>SQL 异常 → Optional.empty() + error counter</li>
 *   <li>正常 hit/miss 各有 counter 与 timer</li>
 * </ul>
 */
public class PgMetricsTokenStore implements MetricsQueryPort {

    private static final Logger log = LoggerFactory.getLogger(PgMetricsTokenStore.class);

    private static final String METRIC_IN = "llm_tokens_in";
    private static final String METRIC_OUT = "llm_tokens_out";
    private static final String WINDOW_HOURS_PLACEHOLDER = "__WINDOW_HOURS__";

    private static final String SQL_AGG =
            "SELECT metric_name, COALESCE(SUM(value), 0) AS total " +
            "  FROM metrics_samples " +
            " WHERE metric_name IN (?, ?) " +
            "   AND ts > now() - INTERVAL '" + WINDOW_HOURS_PLACEHOLDER + " hours' " +
            "   AND tags->>'trace_id' = ? " +
            " GROUP BY metric_name";

    /** Sprint 2 P4.3:tag 缺失探测 SQL — 用于判断数据存在但 trace_id tag 缺失。 */
    private static final String SQL_PROBE =
            "SELECT COUNT(*) FROM metrics_samples " +
            " WHERE metric_name IN (?, ?) " +
            "   AND ts > now() - INTERVAL '" + WINDOW_HOURS_PLACEHOLDER + " hours'";

    private final DataSource dataSource;
    private final long windowHours;
    private final MeterRegistry meterRegistry;
    private final Counter hitCounter;
    private final Counter missCounter;
    private final Counter tagMissingCounter;
    private final Counter errorCounter;
    private final Timer lookupTimer;

    public PgMetricsTokenStore(DataSource dataSource) {
        this(dataSource, 24, null);
    }

    public PgMetricsTokenStore(DataSource dataSource, long windowHours) {
        this(dataSource, windowHours, null);
    }

    public PgMetricsTokenStore(DataSource dataSource, long windowHours, MeterRegistry meterRegistry) {
        this.dataSource = dataSource;
        this.windowHours = windowHours;
        this.meterRegistry = meterRegistry;
        if (meterRegistry != null) {
            this.hitCounter = Counter.builder("replay_metrics_token_lookup_total")
                    .tags(Tags.of("result", "hit")).register(meterRegistry);
            this.missCounter = Counter.builder("replay_metrics_token_lookup_total")
                    .tags(Tags.of("result", "miss")).register(meterRegistry);
            this.tagMissingCounter = Counter.builder("replay_metrics_token_lookup_total")
                    .tags(Tags.of("result", "tag_missing")).register(meterRegistry);
            this.errorCounter = Counter.builder("replay_metrics_token_lookup_total")
                    .tags(Tags.of("result", "error")).register(meterRegistry);
            this.lookupTimer = Timer.builder("replay_metrics_token_lookup_duration_seconds")
                    .register(meterRegistry);
        } else {
            this.hitCounter = null;
            this.missCounter = null;
            this.tagMissingCounter = null;
            this.errorCounter = null;
            this.lookupTimer = null;
        }
    }

    @Override
    public Optional<Tokens> findTokensForTrace(String traceId) {
        long startNs = System.nanoTime();
        try {
            if (traceId == null || traceId.isBlank()) {
                recordMiss();
                return Optional.empty();
            }
            Map<String, Double> totals = new HashMap<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         SQL_AGG.replace(WINDOW_HOURS_PLACEHOLDER, String.valueOf(windowHours)))) {
                ps.setString(1, METRIC_IN);
                ps.setString(2, METRIC_OUT);
                ps.setString(3, traceId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        totals.put(rs.getString("metric_name"), rs.getDouble("total"));
                    }
                }
            } catch (SQLException e) {
                log.warn("findTokensForTrace({}) failed: {}", traceId, e.getMessage());
                recordError();
                return Optional.empty();
            }

            if (totals.isEmpty()) {
                // Sprint 2 P4.3:trace_id tag 缺失探测 — 数据存在但 tag 缺失
                if (hasAnyTokenMetricInWindow()) {
                    recordTagMissing();
                    log.debug("metrics_samples 有数据但无 trace_id={} 的 tag", traceId);
                } else {
                    recordMiss();
                }
                return Optional.empty();
            }

            int in = totals.getOrDefault(METRIC_IN, 0.0).intValue();
            int out = totals.getOrDefault(METRIC_OUT, 0.0).intValue();
            if (in == 0 && out == 0) {
                recordMiss();
                return Optional.empty();
            }
            recordHit();
            return Optional.of(new Tokens(in, out));
        } finally {
            if (lookupTimer != null) {
                lookupTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
            }
        }
    }

    /** 探测窗口内是否至少有一行 llm_tokens_in/out 数据。 */
    private boolean hasAnyTokenMetricInWindow() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     SQL_PROBE.replace(WINDOW_HOURS_PLACEHOLDER, String.valueOf(windowHours)))) {
            ps.setString(1, METRIC_IN);
            ps.setString(2, METRIC_OUT);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1) > 0;
            }
        } catch (SQLException e) {
            log.warn("hasAnyTokenMetricInWindow probe failed: {}", e.getMessage());
        }
        return false;
    }

    private void recordHit() { if (hitCounter != null) hitCounter.increment(); }
    private void recordMiss() { if (missCounter != null) missCounter.increment(); }
    private void recordTagMissing() { if (tagMissingCounter != null) tagMissingCounter.increment(); }
    private void recordError() { if (errorCounter != null) errorCounter.increment(); }
}