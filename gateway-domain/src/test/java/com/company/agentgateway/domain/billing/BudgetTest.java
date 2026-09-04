package com.company.agentgateway.domain.billing;

import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class BudgetTest {

    @Test
    void alertThreshold_percentOutOfRange_throws() {
        assertThatThrownBy(() -> new AlertThreshold(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AlertThreshold(101)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new AlertThreshold(80).percent()).isEqualTo(80);
    }

    @Test
    void nullTenant_throws() {
        assertThatThrownBy(() -> new Budget(null, null, BudgetType.MONEY,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
                new AlertThreshold(80), false, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeLimits_throws() {
        TenantId t = new TenantId("t1");
        assertThatThrownBy(() -> new Budget(t, null, BudgetType.MONEY,
                BigDecimal.valueOf(-1), BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
                new AlertThreshold(80), false, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void suspendRequiresSuspendAction() {
        TenantId t = new TenantId("t1");
        assertThatThrownBy(() -> new Budget(t, null, BudgetType.MONEY,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
                new AlertThreshold(80), false, null, Instant.now().plusSeconds(600)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("suspendAction=SUSPEND");
    }

    @Test
    void suspendAction_mustHaveFutureSuspendUntil() {
        TenantId t = new TenantId("t1");
        assertThatThrownBy(() -> new Budget(t, null, BudgetType.MONEY,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
                new AlertThreshold(80), false, Budget.QuotaAction.SUSPEND, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("suspendUntil");
    }

    @Test
    void validConstruction_suspendAction_carriesAllFields() {
        TenantId t = new TenantId("t1");
        Instant until = Instant.now().plusSeconds(300);
        Budget b = new Budget(t, null, BudgetType.MONEY,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
                new AlertThreshold(80), false, Budget.QuotaAction.SUSPEND, until);
        assertThat(b.tenant()).isEqualTo(t);
        assertThat(b.suspendAction()).isEqualTo(Budget.QuotaAction.SUSPEND);
        assertThat(b.suspendUntil()).isEqualTo(until);
        assertThat(b.alertSent()).isFalse();
    }

    // ================= P1：超限动作（overLimitAction / fallbackModel） =================

    @Test
    void overLimitAction_null默认BLOCK() {
        TenantId t = new TenantId("t1");
        Budget b = new Budget(t, null, BudgetType.MONEY,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
                new AlertThreshold(80), false, null, null);
        assertThat(b.overLimitAction()).isEqualTo(Budget.OverLimitAction.BLOCK);
        assertThat(b.fallbackModel()).isNull();
    }

    @Test
    void downgrade_缺fallbackModel_拒绝() {
        TenantId t = new TenantId("t1");
        assertThatThrownBy(() -> new Budget(t, null, BudgetType.MONEY,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
                new AlertThreshold(80), false, null, null,
                Budget.OverLimitAction.DOWNGRADE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fallbackModel");
        assertThatThrownBy(() -> new Budget(t, null, BudgetType.MONEY,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
                new AlertThreshold(80), false, null, null,
                Budget.OverLimitAction.DOWNGRADE, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fallbackModel");
    }

    @Test
    void downgrade_带fallbackModel_合法() {
        TenantId t = new TenantId("t1");
        Budget b = new Budget(t, null, BudgetType.MONEY,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
                new AlertThreshold(80), false, null, null,
                Budget.OverLimitAction.DOWNGRADE, "qwen-turbo");
        assertThat(b.overLimitAction()).isEqualTo(Budget.OverLimitAction.DOWNGRADE);
        assertThat(b.fallbackModel()).isEqualTo("qwen-turbo");
    }
}
