package com.company.agentgateway.domain.billing;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BillingExceptionsTest {

    @Test
    void quotaExceeded_carriesErrorCodeAndMessage() {
        QuotaExceededException ex = new QuotaExceededException("GW-4304", "quota exhausted");
        assertThat(ex.getMessage()).contains("GW-4304");
        assertThat(ex.getMessage()).contains("quota exhausted");
        assertThat(ex.getErrorCode()).isEqualTo("GW-4304");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void quotaExceeded_supportsCauseChain() {
        Throwable cause = new RuntimeException("redis down");
        QuotaExceededException ex = new QuotaExceededException("GW-4305", "suspended", cause);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void budgetConfiguration_carriesErrorCode() {
        BudgetConfigurationException ex = new BudgetConfigurationException("GW-4306", "policy invalid");
        assertThat(ex.getMessage()).contains("GW-4306");
        assertThat(ex.getErrorCode()).isEqualTo("GW-4306");
    }

    @Test
    void billingErrorCode_constants_alignWithSpec() {
        // spec §13.4 + §21.6 已规划 4301~4303；D2 新增 4304~4306
        assertThat(BillingErrorCode.BILLING_QUERY_INVALID).isEqualTo("GW-4301");
        assertThat(BillingErrorCode.BUDGET_CONFIG_CONFLICT).isEqualTo("GW-4302");
        assertThat(BillingErrorCode.BILLING_EXPORT_FAILED).isEqualTo("GW-4303");
        assertThat(BillingErrorCode.QUOTA_HARD_LIMIT).isEqualTo("GW-4304");
        assertThat(BillingErrorCode.TENANT_SUSPENDED).isEqualTo("GW-4305");
        assertThat(BillingErrorCode.QUOTA_POLICY_INVALID).isEqualTo("GW-4306");
    }

    @Test
    void billingErrorCode_allUnique() {
        String[] all = {
            BillingErrorCode.BILLING_QUERY_INVALID,
            BillingErrorCode.BUDGET_CONFIG_CONFLICT,
            BillingErrorCode.BILLING_EXPORT_FAILED,
            BillingErrorCode.QUOTA_HARD_LIMIT,
            BillingErrorCode.TENANT_SUSPENDED,
            BillingErrorCode.QUOTA_POLICY_INVALID
        };
        assertThat(java.util.Set.of(all)).hasSize(all.length);
    }
}
