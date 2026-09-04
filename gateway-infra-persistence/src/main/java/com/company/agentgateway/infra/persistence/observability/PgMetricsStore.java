package com.company.agentgateway.infra.persistence.observability;

import com.company.agentgateway.domain.observability.MetricPoint;
import com.company.agentgateway.domain.observability.MetricQueryRepository;
import com.company.agentgateway.domain.observability.MetricQueryRepository.MetricBucket;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

/**
 * MetricQueryRepository 的 PG/TimescaleDB 实现(spec 2026-08-19 §4.2/§5.2)。
 *
 * <p>写入:PgMetricsPublisher 每 30s delta 快照后批量落库。
 * 查询:趋势序列(原始表 14 天内,rollup 由查询侧按范围自动选择 —— 首版直接查原始表,
 * 长范围优化二期接 continuous aggregate)。
 */
public class PgMetricsStore implements MetricQueryRepository, MetricsWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PgMetricsStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public int batchInsert(List<MetricPoint> points) {
        if (points.isEmpty()) return 0;
        jdbc.batchUpdate(
                "INSERT INTO metrics_samples (metric_name, tags, ts, value) VALUES (?, ?::jsonb, ?, ?)",
                points.stream().map(p -> new Object[]{
                        p.metricName(), toJson(p.tags()), Timestamp.from(p.ts()), p.value()
                }).toList());
        return points.size();
    }

    @Override
    public List<MetricPoint> querySeries(String metricName, Map<String, String> tags,
                                         java.time.Instant from, java.time.Instant to) {
        StringBuilder where = new StringBuilder(" WHERE metric_name = ? AND ts >= ? AND ts < ?");
        List<Object> args = new ArrayList<>(List.of(metricName, Timestamp.from(from), Timestamp.from(to)));
        // tags 精确匹配:逐键 jsonb 等值
        for (Map.Entry<String, String> e : tags.entrySet()) {
            where.append(" AND tags->>? = ?");
            args.add(e.getKey());
            args.add(e.getValue());
        }
        where.append(" ORDER BY ts ASC LIMIT 10000");
        return jdbc.query("SELECT metric_name, tags, ts, value FROM metrics_samples" + where,
                (rs, i) -> new MetricPoint(
                        rs.getString("metric_name"),
                        readTags(rs.getString("tags")),
                        rs.getTimestamp("ts").toInstant(),
                        rs.getDouble("value")),
                args.toArray());
    }

    @Override
    public OptionalDouble windowSum(String metricName, Map<String, String> tags,
                                    java.time.Instant from, java.time.Instant to) {
        StringBuilder where = new StringBuilder(" WHERE metric_name = ? AND ts >= ? AND ts < ?");
        List<Object> args = new ArrayList<>(List.of(metricName, Timestamp.from(from), Timestamp.from(to)));
        for (Map.Entry<String, String> e : tags.entrySet()) {
            where.append(" AND tags->>? = ?");
            args.add(e.getKey());
            args.add(e.getValue());
        }
        List<Double> r = jdbc.query(
                "SELECT coalesce(sum(value), 0) AS s FROM metrics_samples" + where,
                (rs, i) -> rs.getDouble("s"), args.toArray());
        return r.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(r.get(0));
    }

    @Override
    public List<MetricBucket> queryBuckets(String metricName, Map<String, String> tags,
                                           java.time.Instant from, java.time.Instant to, int bucketSeconds) {
        StringBuilder where = new StringBuilder(" WHERE metric_name = ? AND ts >= ? AND ts < ?");
        List<Object> args = new ArrayList<>(List.of(metricName, Timestamp.from(from), Timestamp.from(to)));
        for (Map.Entry<String, String> e : tags.entrySet()) {
            where.append(" AND tags->>? = ?");
            args.add(e.getKey());
            args.add(e.getValue());
        }
        // 原始表 time_bucket 分桶(14 天内数据;更长范围后续切 continuous aggregate)
        return jdbc.query(
                "SELECT time_bucket(?) AS bucket, sum(value) AS s FROM metrics_samples" + where
                        + " GROUP BY bucket ORDER BY bucket ASC",
                (rs, i) -> new MetricBucket(
                        rs.getTimestamp("bucket").toInstant(), rs.getDouble("s")),
                prepend(bucketSeconds * 1_000_000_000L, args).toArray());
    }

    private static List<Object> prepend(Object v, List<Object> rest) {
        List<Object> out = new ArrayList<>(rest.size() + 1);
        out.add(v);
        out.addAll(rest);
        return out;
    }

    private String toJson(Map<String, String> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readTags(String s) {
        try {
            Map<String, Object> raw = objectMapper.readValue(s == null ? "{}" : s, Map.class);
            return raw.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> String.valueOf(e.getValue())));
        } catch (Exception e) {
            return Map.of();
        }
    }
}
