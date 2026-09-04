package com.company.agentgateway.infra.persistence.billing;

import com.company.agentgateway.domain.billing.AlertThreshold;
import com.company.agentgateway.domain.billing.Budget;
import com.company.agentgateway.domain.billing.BudgetType;
import com.company.agentgateway.domain.billing.ExportFormat;
import com.company.agentgateway.domain.billing.UsageAtom;
import com.company.agentgateway.domain.billing.UsageQuery;
import com.company.agentgateway.domain.billing.UsageRecord;
import com.company.agentgateway.domain.iam.AgentPermission;
import com.company.agentgateway.domain.iam.ModelPermission;
import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.iam.RoleBinding;
import com.company.agentgateway.domain.quota.QuotaDecision;
import com.company.agentgateway.domain.quota.QuotaDimension;
import com.company.agentgateway.domain.quota.QuotaKey;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import com.company.agentgateway.infra.persistence.quota.PgQuotaRepository;
import com.company.agentgateway.infra.persistence.rbac.PgRoleBindingRepository;
import com.company.agentgateway.infra.persistence.rbac.PgRoleRepository;
import com.company.agentgateway.infra.persistence.observability.TestDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 计费/配额/预算 + RBAC PG 持久化 IT（add-pg-persistence）。
 *
 * <p>不依赖 Spring 上下文：TestDb（Testcontainers TimescaleDB / PG_URL）+ 手工 wire。
 * 核心断言：**仓储层重启等价**——新建仓储实例（模拟进程重启）后数据仍在。
 *
 * <p>激活：mvn -Pit -pl gateway-infra-persistence verify（默认回归不跑本类）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PgBillingRbacStoresIT {

    private static JdbcTemplate jdbc;
    private static final TenantId T = new TenantId("it-tenant");
    private static final ModelId M = new ModelId("m1");

    @BeforeAll
    static void setUp() {
        DataSource ds = TestDb.connect();
        jdbc = new JdbcTemplate(ds);
        new PgBillingRbacSchemaInitializer(ds).initialize();
    }

    // ---------- BillingPort ----------

    @Test
    @Order(1)
    void billingRecord_roundtripSurvivesRestart() {
        jdbc.update("DELETE FROM billing_records WHERE tenant_id = ?", T.value()); // 清场（幂等重跑）
        PgBillingRepository repo = new PgBillingRepository(jdbc);
        String rid = "r-it-" + java.util.UUID.randomUUID();
        repo.recordUsage(new UsageRecord(rid, T, new UserId("u1"), M, "agent",
                Instant.now(), 123, 45, new BigDecimal("0.50"),
                new BigDecimal("0.001"), new BigDecimal("0.002")));

        // 模拟重启：新实例读同一张表
        List<UsageRecord> got = new PgBillingRepository(jdbc).queryUsage(
                new UsageQuery(T, Instant.now().minusSeconds(60), Instant.now().plusSeconds(60), null, null));
        assertThat(got).hasSize(1);
        assertThat(got.get(0).recordId()).isEqualTo(rid);
        assertThat(got.get(0).tokensIn()).isEqualTo(123);
        assertThat(got.get(0).unitPriceIn()).isEqualByComparingTo("0.001"); // 单价快照

        // 成本聚合
        BigDecimal cost = new PgBillingRepository(jdbc).queryCost(
                new UsageQuery(T, null, null, null, null));
        assertThat(cost).isEqualByComparingTo("0.50");
    }

    // ---------- BudgetRepository ----------

    @Test
    @Order(2)
    void budget_upsertAccumulateAlertSent_survivesRestart() {
        PgBillingRepository dummy = null; // 无用占位，保持结构清晰
        PgBudgetRepository repo = new PgBudgetRepository(jdbc);
        repo.save(new Budget(T, null, BudgetType.MONEY,
                new BigDecimal("50"), new BigDecimal("100"),
                BigDecimal.ZERO, BigDecimal.ZERO, new AlertThreshold(80),
                false, null, null));

        // 原子累加 2 次
        repo.accumulateUsage(T, new BigDecimal("10"));
        repo.accumulateUsage(T, new BigDecimal("5"));

        // 模拟重启后读取：累计值持久化
        Budget loaded = new PgBudgetRepository(jdbc).findByTenant(T).orElseThrow();
        assertThat(loaded.currentDailyUsed()).isEqualByComparingTo("15");
        assertThat(loaded.alertThreshold().percent()).isEqualTo(80);

        // 告警标记幂等：第一次 true，第二次 false
        assertThat(repo.markAlertSent(T)).isTrue();
        assertThat(repo.markAlertSent(T)).isFalse();
        assertThat(new PgBudgetRepository(jdbc).findByTenant(T).orElseThrow().alertSent()).isTrue();

        // 清理（供后续用例重复跑）
        repo.delete(T);
        assertThat(new PgBudgetRepository(jdbc).findByTenant(T)).isEmpty();
        assertThat(dummy).isNull();
    }

    // ---------- QuotaPort ----------

    @Test
    @Order(3)
    void quotaCounter_consumeCheckReverse_survivesRestart() {
        PgQuotaRepository repo = new PgQuotaRepository(jdbc);
        QuotaKey key = new QuotaKey(T, M, QuotaDimension.MODEL_TOKEN);

        repo.consume(key, new UsageAtom(1, 3000, 100, BigDecimal.ONE));

        // 模拟重启：计数仍在，check 按 3000 起算
        QuotaDecision decision = new PgQuotaRepository(jdbc).check(
                key, new UsageAtom(0, 8000, 0, BigDecimal.ZERO));
        assertThat(decision).isInstanceOf(QuotaDecision.Rejected.class); // 3000+8000 > 10000

        // 回滚 3000 → 0，再 check 放行
        new PgQuotaRepository(jdbc).reverse(key, new UsageAtom(1, 3000, 0, BigDecimal.ZERO));
        QuotaDecision ok = new PgQuotaRepository(jdbc).check(
                key, new UsageAtom(0, 6000, 0, BigDecimal.ZERO));
        assertThat(ok).isInstanceOf(QuotaDecision.Allowed.class);

        // snapshot 非空
        assertThat(new PgQuotaRepository(jdbc).snapshot(T)).isNotEmpty();
    }

    // ---------- RBAC ----------

    @Test
    @Order(4)
    void rbacRoleAndBinding_roundtrip_survivesRestart() {
        PgRoleRepository roleRepo = new PgRoleRepository(jdbc);
        PgRoleBindingRepository bindRepo = new PgRoleBindingRepository(jdbc);
        RoleId roleId = new RoleId("role-it-1");

        Set<com.company.agentgateway.domain.iam.Permission> perms = new LinkedHashSet<>();
        perms.add(new AgentPermission("echo-agent", Set.of("run")));
        perms.add(new ModelPermission(Set.of(new ModelId("qwen-max"))));
        roleRepo.save(T, new Role(roleId, "IT测试角色", "desc", perms));

        bindRepo.bind(T, new UserId("u-it"), roleId);

        // 模拟重启后读取：角色（含 sealed Permission 三型 JSON 往返）+ 绑定仍在
        Role loaded = new PgRoleRepository(jdbc).findById(T, roleId).orElseThrow();
        assertThat(loaded.name()).isEqualTo("IT测试角色");
        assertThat(loaded.permissions()).containsExactlyInAnyOrderElementsOf(perms);

        List<RoleId> roles = new PgRoleBindingRepository(jdbc).findByUser(T, new UserId("u-it"));
        assertThat(roles).containsExactly(roleId);

        // 清理（幂等 unbind + delete）
        new PgRoleBindingRepository(jdbc).unbind(T, new UserId("u-it"), roleId);
        new PgRoleRepository(jdbc).delete(T, roleId);
        assertThat(new PgRoleRepository(jdbc).findById(T, roleId)).isEmpty();
    }
}
