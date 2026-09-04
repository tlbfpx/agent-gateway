package com.company.agentgateway.domain.billing;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class UsageRecordTest {

    @Test
    void blankRecordId_throws() {
        assertThatThrownBy(() -> new UsageRecord("", new TenantId("t1"), new UserId("u1"),
                new ModelId("m1"), "agent", Instant.now(), 100, 50, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recordId");
    }

    @Test
    void negativeTokens_throws() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> new UsageRecord("r1", new TenantId("t1"), new UserId("u1"),
                new ModelId("m1"), "agent", now, -1, 50, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeCost_throws() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> new UsageRecord("r1", new TenantId("t1"), new UserId("u1"),
                new ModelId("m1"), "agent", now, 100, 50, BigDecimal.valueOf(-1), BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeUnitPrice_throws() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> new UsageRecord("r1", new TenantId("t1"), new UserId("u1"),
                new ModelId("m1"), "agent", now, 100, 50, BigDecimal.ONE, BigDecimal.valueOf(-1), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validConstruction_carriesAllFields() {
        TenantId t = new TenantId("t1"); UserId u = new UserId("u1"); ModelId m = new ModelId("qwen");
        Instant now = Instant.now();
        BigDecimal cost = new BigDecimal("0.015");
        BigDecimal priceIn = new BigDecimal("0.0001");
        BigDecimal priceOut = new BigDecimal("0.0003");
        UsageRecord r = new UsageRecord("r1", t, u, m, "agent", now, 100, 50, cost, priceIn, priceOut);
        assertThat(r.recordId()).isEqualTo("r1");
        assertThat(r.tenant()).isEqualTo(t);
        assertThat(r.user()).isEqualTo(u);
        assertThat(r.model()).isEqualTo(m);
        assertThat(r.agentName()).isEqualTo("agent");
        assertThat(r.timestamp()).isEqualTo(now);
        assertThat(r.tokensIn()).isEqualTo(100);
        assertThat(r.tokensOut()).isEqualTo(50);
        assertThat(r.cost()).isEqualByComparingTo(cost);
        assertThat(r.unitPriceIn()).isEqualByComparingTo(priceIn);
        assertThat(r.unitPriceOut()).isEqualByComparingTo(priceOut);
    }

    @Test
    void equalsAndHashCode() {
        TenantId t = new TenantId("t1"); UserId u = new UserId("u1"); ModelId m = new ModelId("m1");
        Instant now = Instant.now();
        UsageRecord a = new UsageRecord("r1", t, u, m, "agent", now, 100, 50, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        UsageRecord b = new UsageRecord("r1", t, u, m, "agent", now, 100, 50, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}
