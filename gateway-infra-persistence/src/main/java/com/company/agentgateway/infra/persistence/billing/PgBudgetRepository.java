package com.company.agentgateway.infra.persistence.billing;

import com.company.agentgateway.domain.billing.AlertThreshold;
import com.company.agentgateway.domain.billing.Budget;
import com.company.agentgateway.domain.billing.BudgetRepository;
import com.company.agentgateway.domain.billing.BudgetType;
import com.company.agentgateway.domain.shared.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * BudgetRepository 的 PG 实现（add-pg-persistence）。
 *
 * <p>每租户一行（PK tenant_id，upsert 语义）；accumulateUsage 用 SQL 原子自增，
 * 多实例并发累加安全。替代 InMemoryBudgetRepository（保留为降级）。
 */
public class PgBudgetRepository implements BudgetRepository {

    private final JdbcTemplate jdbc;

    public PgBudgetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Budget> findByTenant(TenantId tenant) {
        List<Budget> rows = jdbc.query(
                "SELECT * FROM budgets WHERE tenant_id = ?",
                (rs, i) -> mapRow(rs.getString("tenant_id"), rs),
                tenant.value());
        return rows.stream().findFirst();
    }

    @Override
    public void save(Budget b) {
        jdbc.update("""
                INSERT INTO budgets (tenant_id, budget_type, daily_limit, monthly_limit,
                                     current_daily_used, current_monthly_used,
                                     alert_threshold_pct, alert_sent, suspend_action, suspend_until,
                                     over_limit_action, fallback_model)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id) DO UPDATE SET
                    budget_type = EXCLUDED.budget_type,
                    daily_limit = EXCLUDED.daily_limit,
                    monthly_limit = EXCLUDED.monthly_limit,
                    current_daily_used = EXCLUDED.current_daily_used,
                    current_monthly_used = EXCLUDED.current_monthly_used,
                    alert_threshold_pct = EXCLUDED.alert_threshold_pct,
                    alert_sent = EXCLUDED.alert_sent,
                    suspend_action = EXCLUDED.suspend_action,
                    suspend_until = EXCLUDED.suspend_until,
                    over_limit_action = EXCLUDED.over_limit_action,
                    fallback_model = EXCLUDED.fallback_model
                """,
                b.tenant().value(), b.type().name(), b.dailyLimit(), b.monthlyLimit(),
                b.currentDailyUsed(), b.currentMonthlyUsed(),
                b.alertThreshold().percent(), b.alertSent(),
                b.suspendAction() != null ? b.suspendAction().name() : null,
                b.suspendUntil() != null ? Timestamp.from(b.suspendUntil()) : null,
                b.overLimitAction() != null ? b.overLimitAction().name() : null,
                b.fallbackModel());
    }

    @Override
    public void delete(TenantId tenant) {
        jdbc.update("DELETE FROM budgets WHERE tenant_id = ?", tenant.value());
    }

    @Override
    public boolean markAlertSent(TenantId tenant) {
        // 幂等：仅 alert_sent=false 时更新，返回是否本次生效
        return jdbc.update(
                "UPDATE budgets SET alert_sent = TRUE WHERE tenant_id = ? AND alert_sent = FALSE",
                tenant.value()) > 0;
    }

    @Override
    public void accumulateUsage(TenantId tenant, BigDecimal amount) {
        // SQL 原子自增（行不存在则忽略——无预算 = 无监控，与 BudgetGuard 语义一致）
        jdbc.update("""
                UPDATE budgets SET current_daily_used = current_daily_used + ?,
                                   current_monthly_used = current_monthly_used + ?
                WHERE tenant_id = ?
                """, amount, amount, tenant.value());
    }

    private Budget mapRow(String tenant, java.sql.ResultSet rs) throws java.sql.SQLException {
        Budget.QuotaAction suspendAction = rs.getString("suspend_action") != null
                ? Budget.QuotaAction.valueOf(rs.getString("suspend_action")) : null;
        Timestamp until = rs.getTimestamp("suspend_until");
        // 注意：suspendUntil 非 future 时（冷静期已过）置 null 再构造，避免 Budget 校验抛错
        if (suspendAction != null && suspendAction != Budget.QuotaAction.SUSPEND) {
            suspendAction = null;
        }
        Instant suspendUntil = until != null ? until.toInstant() : null;
        if (suspendUntil != null && !suspendUntil.isAfter(Instant.now())) {
            suspendUntil = null; // 冷静期已过，按无 SUSPEND 处理
        }
        String overLimitActionStr = rs.getString("over_limit_action");
        Budget.OverLimitAction overLimitAction = overLimitActionStr != null
                ? Budget.OverLimitAction.valueOf(overLimitActionStr) : null;
        String fallbackModel = rs.getString("fallback_model");
        if (overLimitAction == Budget.OverLimitAction.BLOCK) fallbackModel = null;
        return new Budget(
                new TenantId(tenant), null, BudgetType.valueOf(rs.getString("budget_type")),
                rs.getBigDecimal("daily_limit"), rs.getBigDecimal("monthly_limit"),
                rs.getBigDecimal("current_daily_used"), rs.getBigDecimal("current_monthly_used"),
                new AlertThreshold(rs.getInt("alert_threshold_pct")),
                rs.getBoolean("alert_sent"),
                suspendAction, suspendUntil, overLimitAction, fallbackModel);
    }
}
