package com.company.agentgateway.domain.billing;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

class CostRecordTest {

    @Test
    void negativeTokens_throws() {
        assertThatThrownBy(() -> new CostRecord("c1", new TenantId("t1"), new UserId("u1"),
                new ModelId("m1"), "agent", LocalDate.now(), -1, 50, BigDecimal.ONE, "CNY"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankCurrency_throws() {
        assertThatThrownBy(() -> new CostRecord("c1", new TenantId("t1"), new UserId("u1"),
                new ModelId("m1"), "agent", LocalDate.now(), 100, 50, BigDecimal.ONE, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void currencyDefaultsToCNY_whenNull() {
        CostRecord r = new CostRecord("c1", new TenantId("t1"), new UserId("u1"),
                new ModelId("m1"), "agent", LocalDate.now(), 100, 50, BigDecimal.ONE, null);
        assertThat(r.currency()).isEqualTo("CNY");
    }

    @Test
    void validConstruction_carriesAllFields() {
        LocalDate date = LocalDate.of(2026, 8, 26);
        CostRecord r = new CostRecord("c1", new TenantId("t1"), new UserId("u1"),
                new ModelId("m1"), "agent", date, 1000, 500, new BigDecimal("1.50"), "USD");
        assertThat(r.id()).isEqualTo("c1");
        assertThat(r.date()).isEqualTo(date);
        assertThat(r.totalTokensIn()).isEqualTo(1000);
        assertThat(r.totalTokensOut()).isEqualTo(500);
        assertThat(r.totalCost()).isEqualByComparingTo("1.50");
        assertThat(r.currency()).isEqualTo("USD");
    }
}
