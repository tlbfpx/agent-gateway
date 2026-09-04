package com.company.agentgateway.infra.persistence.observability;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.shared.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * AuditRepository 的 PG 实现(spec 2026-08-19 §4.4):append-only 落 audit_events hypertable,
 * 替代 InMemoryAuditRepository(后者保留为无 PG 时的降级)。
 */
public class PgAuditStore implements AuditRepository {

    private final JdbcTemplate jdbc;

    public PgAuditStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(AuditLog log) {
        jdbc.update("""
                INSERT INTO audit_events (event_id, tenant, actor, actor_type, event_type, ts,
                                          resource_type, resource_id, action, result, error_message, start_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                log.eventId(), log.tenant().value(), log.actor(), log.actorType().name(),
                log.eventType().name(), Timestamp.from(log.timestamp()),
                log.resourceType(), log.resourceId(), log.action(), log.result().name(),
                log.errorMessage(), Timestamp.from(log.timestamp()));
    }

    @Override
    public List<AuditLog> query(TenantId tenant, AuditEventType type, Instant from, Instant to, int limit) {
        return query(new AuditQuery(tenant, type, from, to, null, null, limit, 0));
    }

    /** 扩展查询：SQL 层完成 result/keyword 过滤与 offset 分页。 */
    @Override
    public List<AuditLog> query(AuditQuery q) {
        StringBuilder where = new StringBuilder(" WHERE tenant = ?");
        List<Object> args = new ArrayList<>();
        args.add(q.tenant().value());
        if (q.type() != null) { where.append(" AND event_type = ?"); args.add(q.type().name()); }
        if (q.from() != null) { where.append(" AND ts >= ?"); args.add(Timestamp.from(q.from())); }
        if (q.to() != null) { where.append(" AND ts < ?"); args.add(Timestamp.from(q.to())); }
        if (q.result() != null) { where.append(" AND result = ?"); args.add(q.result().name()); }
        if (q.keyword() != null) {
            String like = "%" + q.keyword().toLowerCase() + "%";
            where.append(" AND (LOWER(actor) LIKE ? OR LOWER(resource_id) LIKE ? OR LOWER(COALESCE(error_message, '')) LIKE ?)");
            args.add(like); args.add(like); args.add(like);
        }
        where.append(" ORDER BY ts DESC LIMIT ? OFFSET ?");
        args.add(q.limit());
        args.add(q.offset());
        return jdbc.query(
                "SELECT event_id, tenant, actor, actor_type, event_type, ts, resource_type, " +
                "resource_id, action, result, error_message FROM audit_events" + where,
                (rs, i) -> new AuditLog(
                        rs.getString("event_id"),
                        new TenantId(rs.getString("tenant")),
                        rs.getString("actor"),
                        AuditLog.ActorType.valueOf(rs.getString("actor_type")),
                        AuditEventType.valueOf(rs.getString("event_type")),
                        rs.getTimestamp("ts").toInstant(),
                        rs.getString("resource_type"),
                        rs.getString("resource_id"),
                        rs.getString("action"),
                        AuditLog.Result.valueOf(rs.getString("result")),
                        rs.getString("error_message")),
                args.toArray());
    }
}
