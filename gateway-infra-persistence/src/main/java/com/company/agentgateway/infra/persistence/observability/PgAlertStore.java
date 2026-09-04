package com.company.agentgateway.infra.persistence.observability;

import com.company.agentgateway.domain.observability.AlertStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * AlertStore 的 PG 实现(spec 2026-08-19 §4.3/§5.4)。
 * alert_rules 为普通表(CRUD);alerts 为 hypertable。
 */
public class PgAlertStore implements AlertStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PgAlertStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ================= rules =================

    @Override
    public AlertRule saveRule(AlertRule r) {
        if (r.id() == null || getRule(r.id()).isEmpty()) {
            String id = r.id() == null ? ("ar-" + System.nanoTime()) : r.id();
            jdbc.update("""
                    INSERT INTO alert_rules (id, name, metric_name, operator, threshold,
                                             window_seconds, silence_minutes, dedup_key_tpl,
                                             severity, enabled, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id, r.name(), r.metricName(), r.operator().name(), r.threshold(),
                    r.windowSeconds(), r.silenceMinutes(), r.dedupKeyTpl(), r.severity(),
                    r.enabled(), Timestamp.from(r.createdAt() == null ? Instant.now() : r.createdAt()),
                    Timestamp.from(Instant.now()));
            return getRule(id).orElseThrow();
        }
        jdbc.update("""
                UPDATE alert_rules SET name=?, metric_name=?, operator=?, threshold=?,
                       window_seconds=?, silence_minutes=?, dedup_key_tpl=?, severity=?,
                       enabled=?, updated_at=? WHERE id=?
                """,
                r.name(), r.metricName(), r.operator().name(), r.threshold(),
                r.windowSeconds(), r.silenceMinutes(), r.dedupKeyTpl(), r.severity(),
                r.enabled(), Timestamp.from(Instant.now()), r.id());
        return getRule(r.id()).orElseThrow();
    }

    @Override
    public Optional<AlertRule> getRule(String id) {
        List<AlertRule> r = jdbc.query(RULE_SQL + " WHERE id = ?", this::mapRule, id);
        return r.stream().findFirst();
    }

    @Override
    public List<AlertRule> listRules(boolean enabledOnly) {
        return enabledOnly
                ? jdbc.query(RULE_SQL + " WHERE enabled = TRUE ORDER BY created_at", this::mapRule)
                : jdbc.query(RULE_SQL + " ORDER BY created_at", this::mapRule);
    }

    @Override
    public boolean deleteRule(String id) {
        return jdbc.update("DELETE FROM alert_rules WHERE id = ?", id) > 0;
    }

    private static final String RULE_SQL =
            "SELECT id, name, metric_name, operator, threshold, window_seconds, silence_minutes, " +
            "dedup_key_tpl, severity, enabled, created_at, updated_at FROM alert_rules";

    private AlertRule mapRule(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new AlertRule(
                rs.getString("id"), rs.getString("name"), rs.getString("metric_name"),
                AlertRule.Operator.valueOf(rs.getString("operator")), rs.getDouble("threshold"),
                rs.getInt("window_seconds"), rs.getInt("silence_minutes"),
                rs.getString("dedup_key_tpl"), rs.getString("severity"), rs.getBoolean("enabled"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    // ================= alerts =================

    @Override
    public AlertRecord insertFiring(AlertRecord a) {
        String id = a.id() == null ? "al-" + System.nanoTime() : a.id();
        jdbc.update("""
                INSERT INTO alerts (id, rule_id, severity, state, dedup_key, labels,
                                    first_fired_at, recently_triggered_at, trigger_count,
                                    observed_value, threshold, claimed_by, note, resolved_at, start_time)
                VALUES (?, ?, ?, 'firing', ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, NULL, now())
                """,
                id, a.ruleId(), a.severity(), a.dedupKey(), toJson(a.labels()),
                Timestamp.from(a.firstFiredAt()), Timestamp.from(a.recentlyTriggeredAt()),
                a.triggerCount(), a.observedValue(), a.threshold(), a.claimedBy(), a.note());
        return get(id).orElseThrow();
    }

    @Override
    public Optional<AlertRecord> findLatestByDedupKey(String dedupKey) {
        List<AlertRecord> r = jdbc.query(ALERT_SQL + """
                 WHERE dedup_key = ? ORDER BY (state = 'firing') DESC, start_time DESC LIMIT 1
                """, this::mapAlert, dedupKey);
        return r.stream().findFirst();
    }

    @Override
    public AlertRecord update(AlertRecord a) {
        jdbc.update("""
                UPDATE alerts SET state=?, recently_triggered_at=?, trigger_count=?,
                       observed_value=?, threshold=?, claimed_by=?, note=?, resolved_at=?
                WHERE id=?
                """,
                a.state(), Timestamp.from(a.recentlyTriggeredAt()), a.triggerCount(),
                a.observedValue(), a.threshold(), a.claimedBy(), a.note(),
                a.resolvedAt() == null ? null : Timestamp.from(a.resolvedAt()), a.id());
        return get(a.id()).orElseThrow();
    }

    @Override
    public List<AlertRecord> queryAlerts(String state, String severity, int limit) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (state != null && !state.isBlank()) { where.append(" AND state = ?"); args.add(state); }
        if (severity != null && !severity.isBlank()) { where.append(" AND severity = ?"); args.add(severity); }
        where.append(" ORDER BY (state = 'firing') DESC, recently_triggered_at DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(ALERT_SQL + where, this::mapAlert, args.toArray());
    }

    @Override
    public Optional<AlertRecord> get(String id) {
        List<AlertRecord> r = jdbc.query(ALERT_SQL + " WHERE id = ?", this::mapAlert, id);
        return r.stream().findFirst();
    }

    private static final String ALERT_SQL =
            "SELECT id, rule_id, severity, state, dedup_key, labels, first_fired_at, " +
            "recently_triggered_at, trigger_count, observed_value, threshold, claimed_by, note, resolved_at FROM alerts";

    private AlertRecord mapAlert(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new AlertRecord(
                rs.getString("id"), rs.getString("rule_id"), rs.getString("severity"), rs.getString("state"),
                rs.getString("dedup_key"), readTags(rs.getString("labels")),
                rs.getTimestamp("first_fired_at").toInstant(),
                rs.getTimestamp("recently_triggered_at").toInstant(),
                rs.getInt("trigger_count"), (Double) rs.getObject("observed_value"),
                (Double) rs.getObject("threshold"), rs.getString("claimed_by"), rs.getString("note"),
                rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant());
    }

    // ================= helpers =================

    private String toJson(Map<String, String> m) {
        try { return objectMapper.writeValueAsString(m); } catch (Exception e) { return "{}"; }
    }

    private Map<String, String> readTags(String s) {
        try {
            Map<String, Object> raw = objectMapper.readValue(s == null ? "{}" : s, Map.class);
            return raw.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> String.valueOf(e.getValue())));
        } catch (Exception e) { return Map.of(); }
    }
}
