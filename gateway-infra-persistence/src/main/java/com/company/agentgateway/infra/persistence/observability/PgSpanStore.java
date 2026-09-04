package com.company.agentgateway.infra.persistence.observability;

import com.company.agentgateway.domain.observability.SpanQueryRepository;
import com.company.agentgateway.domain.observability.SpanRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SpanQueryRepository 的 PG/TimescaleDB 实现(spec 2026-08-19 §4.1/§5.2)。
 *
 * <p>写入:PgSpanExporter 批量调用 {@link #batchInsert}。
 * 查询:trace 列表(按 trace_id 聚合)+ 单链路 spans(瀑布图)。
 */
public class PgSpanStore implements SpanQueryRepository, SpanWriter {


    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PgSpanStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ================= 写入(批量) =================

    @Override
    public int batchInsert(List<SpanRecord> spans) {
        if (spans.isEmpty()) return 0;
        int[] results = jdbc.batchUpdate(
                """
                INSERT INTO spans (trace_id, span_id, parent_span_id, name, kind,
                                   start_time, end_time, duration_ms, status, attributes, events)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """,
                spans.stream().map(this::toArgs).toList());
        int n = 0;
        for (int v : results) if (v >= 0 || v == java.sql.Statement.SUCCESS_NO_INFO) n++;
        return n;
    }

    private Object[] toArgs(SpanRecord s) {
        return new Object[]{
                s.traceId(), s.spanId(), s.parentSpanId(), s.name(), s.kind().name(),
                Timestamp.from(s.startTime()),
                s.endTime() == null ? null : Timestamp.from(s.endTime()),
                s.durationMs(),
                s.status().name(),
                toJson(s.attributes()),
                toJson(s.events().stream()
                        .map(e -> Map.of("time", e.time().toString(), "name", e.name(),
                                "attributes", e.attributes()))
                        .toList())
        };
    }

    // ================= 查询 =================

    @Override
    @SuppressWarnings("unchecked")
    public List<TraceSummary> queryTraces(TraceFilter f, int limit, int offset) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (f.from() != null) { where.append(" AND start_time >= ?"); args.add(Timestamp.from(f.from())); }
        if (f.to() != null) { where.append(" AND start_time < ?"); args.add(Timestamp.from(f.to())); }
        if (f.operation() != null && !f.operation().isBlank()) { where.append(" AND name = ?"); args.add(f.operation()); }
        if (f.tenantId() != null && !f.tenantId().isBlank()) { where.append(" AND attributes->>'tenant_id' = ?"); args.add(f.tenantId()); }
        if (Boolean.TRUE.equals(f.errorOnly())) { where.append(" AND status = 'ERROR'"); }
        if (f.minDurationMs() != null) { where.append(" AND duration_ms >= ?"); args.add(f.minDurationMs()); }

        String sql = """
                SELECT trace_id,
                       (array_agg(name ORDER BY start_time ASC))[1]          AS root_name,
                       min(start_time)                                        AS started_at,
                       max(coalesce(duration_ms, 0))                          AS total_ms,
                       count(*)                                               AS span_count,
                       count(*) FILTER (WHERE status = 'ERROR')               AS error_count,
                       array_agg(DISTINCT attributes->>'agent_name') FILTER (WHERE name = 'agent.call') AS agents
                FROM spans%s
                GROUP BY trace_id
                """ .formatted(where);
        // agents 数组含 null 元素时过滤
        sql = "SELECT * FROM (" + sql + ") t ORDER BY started_at DESC LIMIT ? OFFSET ?";
        args.add(limit);
        args.add(offset);

        return jdbc.query(sql, (rs, i) -> {
            java.sql.Array arr = rs.getArray("agents");
            List<String> agents = arr == null ? List.of()
                    : java.util.Arrays.stream((Object[]) arr.getArray())
                        .filter(java.util.Objects::nonNull)
                        .map(String::valueOf).distinct().toList();
            return new TraceSummary(
                    rs.getString("trace_id"),
                    rs.getString("root_name"),
                    rs.getTimestamp("started_at").toInstant(),
                    rs.getDouble("total_ms"),
                    rs.getInt("span_count"),
                    rs.getInt("error_count"),
                    agents);
        }, args.toArray());
    }

    @Override
    public List<SpanRecord> getSpans(String traceId) {
        return jdbc.query(
                "SELECT * FROM spans WHERE trace_id = ? ORDER BY start_time ASC",
                (rs, i) -> fromRow(rs), traceId);
    }

    @SuppressWarnings("unchecked")
    private SpanRecord fromRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> attrs = readJson(rs.getString("attributes"));
        Map<String, String> attributes = new LinkedHashMap<>();
        attrs.forEach((k, v) -> attributes.put(k, String.valueOf(v)));

        List<Map<String, Object>> rawEvents = (List<Map<String, Object>>) (Object) readJsonList(rs.getString("events"));
        List<SpanRecord.SpanEvent> events = rawEvents.stream()
                .map(e -> new SpanRecord.SpanEvent(
                        Instant.parse(String.valueOf(e.get("time"))),
                        String.valueOf(e.get("name")),
                        ((Map<String, Object>) e.getOrDefault("attributes", Map.of())).entrySet().stream()
                                .collect(Collectors.toMap(Map.Entry::getKey, en -> String.valueOf(en.getValue())))))
                .toList();

        Timestamp end = rs.getTimestamp("end_time");
        return new SpanRecord(
                rs.getString("trace_id"),
                rs.getString("span_id"),
                rs.getString("parent_span_id"),
                rs.getString("name"),
                SpanRecord.Kind.valueOf(rs.getString("kind")),
                rs.getTimestamp("start_time").toInstant(),
                end == null ? null : end.toInstant(),
                (Double) rs.getObject("duration_ms"),
                SpanRecord.Status.valueOf(rs.getString("status")),
                attributes,
                events);
    }

    // ================= helpers =================

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> readJson(String s) {
        try {
            return objectMapper.readValue(s == null ? "{}" : s, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<?> readJsonList(String s) {
        try {
            return objectMapper.readValue(s == null ? "[]" : s, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
