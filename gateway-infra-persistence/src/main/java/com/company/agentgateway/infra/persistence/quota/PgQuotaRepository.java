package com.company.agentgateway.infra.persistence.quota;

import com.company.agentgateway.domain.billing.UsageAtom;
import com.company.agentgateway.domain.quota.QuotaDecision;
import com.company.agentgateway.domain.quota.QuotaKey;
import com.company.agentgateway.domain.quota.QuotaPort;
import com.company.agentgateway.domain.shared.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * QuotaPort 的 PG 实现（add-pg-persistence）。
 *
 * <p>计数器落 quota_counters（按自然日 period 键，跨日自动"清零"——新日期行从 0 开始），
 * UPSERT 原子累加，多实例安全；重启不丢当日计数。
 *
 * <p><b>行为对齐 InMemoryQuotaRepository</b>：限额常量 10000；计数维度统一累计
 * tokensIn（三维差异化的 policy 驱动限额为二期，spec §16.2 演进）。
 */
public class PgQuotaRepository implements QuotaPort {

    /** 与 InMemoryQuotaRepository 对齐的默认限额（二期 policy 驱动）。 */
    static final long DEFAULT_LIMIT = 10_000L;

    private final JdbcTemplate jdbc;

    public PgQuotaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public QuotaDecision check(QuotaKey key, UsageAtom predicted) {
        long used = currentUsed(key);
        long totalAfter = used + predicted.tokensIn();
        if (totalAfter > DEFAULT_LIMIT) {
            return new QuotaDecision.Rejected(key.dimension().name(), DEFAULT_LIMIT, totalAfter);
        }
        return new QuotaDecision.Allowed(DEFAULT_LIMIT - totalAfter);
    }

    @Override
    public void consume(QuotaKey key, UsageAtom used) {
        adjust(key, used.tokensIn());
    }

    @Override
    public void reverse(QuotaKey key, UsageAtom used) {
        adjust(key, -used.tokensIn());
    }

    @Override
    public List<QuotaDecision> snapshot(TenantId tenant) {
        return jdbc.query(
                "SELECT model_id, dimension, used_value FROM quota_counters WHERE tenant_id = ? AND period = CURRENT_DATE",
                (rs, i) -> (QuotaDecision) new QuotaDecision.Allowed(
                        DEFAULT_LIMIT - rs.getLong("used_value")),
                tenant.value());
    }

    private long currentUsed(QuotaKey key) {
        Long used = jdbc.queryForObject(
                "SELECT used_value FROM quota_counters WHERE tenant_id = ? AND model_id = ? AND dimension = ? AND period = CURRENT_DATE",
                Long.class, key.tenant().value(), key.model().value(), key.dimension().name());
        return used != null ? used : 0L;
    }

    private void adjust(QuotaKey key, long delta) {
        jdbc.update("""
                INSERT INTO quota_counters (tenant_id, model_id, dimension, period, used_value)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, model_id, dimension, period)
                DO UPDATE SET used_value = quota_counters.used_value + ?
                """,
                key.tenant().value(), key.model().value(), key.dimension().name(),
                Date.valueOf(LocalDate.now()), delta, delta);
    }
}
