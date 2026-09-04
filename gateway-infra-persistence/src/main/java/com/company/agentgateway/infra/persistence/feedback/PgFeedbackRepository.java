package com.company.agentgateway.infra.persistence.feedback;

import com.company.agentgateway.domain.feedback.FeedbackRecord;
import com.company.agentgateway.domain.feedback.FeedbackRepository;
import com.company.agentgateway.domain.feedback.FeedbackRepository.FeedbackQuery;
import com.company.agentgateway.domain.feedback.FeedbackRepository.Summary;
import com.company.agentgateway.domain.feedback.FeedbackSentiment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Feedback Pg 实现（spec 2026-09-02 §pg-persistence §4.1）。
 */
public class PgFeedbackRepository implements FeedbackRepository {

    private final JdbcTemplate jdbc;

    public PgFeedbackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<FeedbackRecord> MAPPER = (rs, n) -> {
        Integer score = (Integer) rs.getObject("score");
        String tagsCsv = rs.getString("tags");
        String metaJson = rs.getString("metadata");
        List<String> tags = tagsCsv == null || tagsCsv.isBlank()
                ? List.of() : List.of(tagsCsv.split("\\|"));
        Map<String, Object> metadata = metaJson == null || metaJson.isBlank()
                ? Map.of() : Map.of();
        return new FeedbackRecord(
                rs.getLong("id"),
                rs.getString("trace_id"),
                rs.getString("span_id"),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("model"),
                FeedbackSentiment.parse(rs.getString("sentiment")),
                score,
                rs.getString("comment"),
                tags,
                metadata,
                rs.getTimestamp("created_at").toInstant());
    };

    @Override
    public long save(FeedbackRecord record) {
        if (record.id() != 0) {
            jdbc.update("UPDATE feedback SET trace_id=?, span_id=?, tenant_id=?, user_id=?, model=?, sentiment=?, score=?, comment=?, tags=?, metadata=? WHERE id=?",
                    record.traceId(), record.spanId(), record.tenantId(), record.userId(),
                    record.model(),
                    record.sentiment().name(), record.score(), record.comment(),
                    String.join("|", record.tags()),
                    serializeMetadata(record),
                    record.id());
            return record.id();
        }
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO feedback (trace_id, span_id, tenant_id, user_id, model, sentiment, score, comment, tags, metadata, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[] { "id" });
            ps.setString(1, record.traceId());
            ps.setString(2, record.spanId());
            ps.setString(3, record.tenantId());
            ps.setString(4, record.userId());
            ps.setString(5, record.model());
            ps.setString(6, record.sentiment().name());
            if (record.score() != null) ps.setInt(7, record.score());
            else ps.setNull(7, java.sql.Types.INTEGER);
            ps.setString(8, record.comment());
            ps.setString(9, String.join("|", record.tags()));
            ps.setString(10, serializeMetadata(record));
            ps.setTimestamp(11, Timestamp.from(record.createdAt()));
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    @Override
    public Optional<FeedbackRecord> findById(long id) {
        return jdbc.query("SELECT * FROM feedback WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    @Override
    public List<FeedbackRecord> findByTraceId(String traceId) {
        return jdbc.query(
                "SELECT * FROM feedback WHERE trace_id = ? ORDER BY created_at DESC",
                MAPPER, traceId);
    }

    @Override
    public List<FeedbackRecord> query(FeedbackQuery query) {
        StringBuilder sql = new StringBuilder("SELECT * FROM feedback WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (query.tenantId() != null) { sql.append(" AND tenant_id = ?"); args.add(query.tenantId()); }
        if (query.traceId() != null) { sql.append(" AND trace_id = ?"); args.add(query.traceId()); }
        if (query.userId() != null) { sql.append(" AND user_id = ?"); args.add(query.userId()); }
        if (query.sentiment() != null) { sql.append(" AND sentiment = ?"); args.add(query.sentiment().name()); }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(query.limit());
        args.add(query.offset());
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    @Override
    public int deleteById(long id) {
        return jdbc.update("DELETE FROM feedback WHERE id = ?", id);
    }

    @Override
    public Summary aggregate(FeedbackQuery query) {
        StringBuilder where = new StringBuilder("WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (query.tenantId() != null) { where.append(" AND tenant_id = ?"); args.add(query.tenantId()); }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT sentiment, COUNT(*) AS cnt FROM feedback " + where + " GROUP BY sentiment",
                args.toArray());
        long total = 0, positive = 0, negative = 0, neutral = 0;
        for (Map<String, Object> r : rows) {
            long cnt = ((Number) r.get("cnt")).longValue();
            total += cnt;
            switch ((String) r.get("sentiment")) {
                case "POSITIVE" -> positive = cnt;
                case "NEGATIVE" -> negative = cnt;
                default -> neutral = cnt;
            }
        }
        double ratio = total == 0 ? 0 : (double) positive / total;
        return new Summary(total, positive, negative, neutral, ratio, 0, List.of(), List.of());
    }

    private static String serializeMetadata(FeedbackRecord r) {
        if (r.metadata() == null || r.metadata().isEmpty()) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(r.metadata());
        } catch (Exception e) {
            return null;
        }
    }
}