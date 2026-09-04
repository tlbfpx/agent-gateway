package com.company.agentgateway.infra.persistence.billing;

import com.company.agentgateway.domain.billing.BillingPort;
import com.company.agentgateway.domain.billing.ExportFormat;
import com.company.agentgateway.domain.billing.UsageQuery;
import com.company.agentgateway.domain.billing.UsageRecord;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * BillingPort 的 PG 实现（add-pg-persistence）。
 *
 * <p>替代 InMemoryBillingRepository（后者保留为无 PG 时的降级）。
 * 表结构见 schema-billing-rbac.sql；单价快照随行落库保证历史账单可复现（spec §21.2）。
 */
public class PgBillingRepository implements BillingPort {

    private final JdbcTemplate jdbc;

    public PgBillingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void recordUsage(UsageRecord r) {
        jdbc.update("""
                INSERT INTO billing_records (record_id, tenant_id, user_id, model_id, agent_name, ts,
                                             tokens_in, tokens_out, unit_price_in, unit_price_out, cost)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (record_id) DO NOTHING
                """,
                r.recordId(), r.tenant().value(), r.user().value(), r.model().value(),
                r.agentName(), Timestamp.from(r.timestamp()),
                r.tokensIn(), r.tokensOut(), r.unitPriceIn(), r.unitPriceOut(), r.cost());
    }

    @Override
    public List<UsageRecord> queryUsage(UsageQuery q) {
        StringBuilder where = new StringBuilder(" WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(q.tenant().value());
        if (q.model() != null) { where.append(" AND model_id = ?"); args.add(q.model().value()); }
        if (q.agentName() != null) { where.append(" AND agent_name = ?"); args.add(q.agentName()); }
        if (q.from() != null) { where.append(" AND ts >= ?"); args.add(Timestamp.from(q.from())); }
        if (q.to() != null) { where.append(" AND ts <= ?"); args.add(Timestamp.from(q.to())); }
        where.append(" ORDER BY ts DESC LIMIT 10000");
        return jdbc.query("SELECT * FROM billing_records" + where, (rs, i) -> new UsageRecord(
                rs.getString("record_id"),
                new TenantId(rs.getString("tenant_id")),
                new UserId(rs.getString("user_id")),
                new ModelId(rs.getString("model_id")),
                rs.getString("agent_name"),
                rs.getTimestamp("ts").toInstant(),
                rs.getLong("tokens_in"),
                rs.getLong("tokens_out"),
                rs.getBigDecimal("cost"),
                rs.getBigDecimal("unit_price_in"),
                rs.getBigDecimal("unit_price_out")), args.toArray());
    }

    @Override
    public BigDecimal queryCost(UsageQuery q) {
        StringBuilder where = new StringBuilder(" WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(q.tenant().value());
        if (q.model() != null) { where.append(" AND model_id = ?"); args.add(q.model().value()); }
        if (q.from() != null) { where.append(" AND ts >= ?"); args.add(Timestamp.from(q.from())); }
        if (q.to() != null) { where.append(" AND ts <= ?"); args.add(Timestamp.from(q.to())); }
        BigDecimal total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(cost), 0) FROM billing_records" + where, BigDecimal.class, args.toArray());
        return total != null ? total : BigDecimal.ZERO;
    }

    @Override
    public List<UsageRecord> exportUsage(UsageQuery q, ExportFormat format) {
        // 一期导出即全量查询（CSV 序列化由 interfaces 层负责）
        return queryUsage(q);
    }
}
