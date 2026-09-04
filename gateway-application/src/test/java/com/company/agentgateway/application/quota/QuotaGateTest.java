package com.company.agentgateway.application.quota;

import com.company.agentgateway.domain.billing.QuotaExceededException;
import com.company.agentgateway.domain.billing.UsageAtom;
import com.company.agentgateway.domain.quota.QuotaDecision;
import com.company.agentgateway.domain.quota.QuotaDimension;
import com.company.agentgateway.domain.quota.QuotaKey;
import com.company.agentgateway.domain.quota.QuotaPort;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuotaGateTest {

    private final QuotaPort quotaPort = mock(QuotaPort.class);
    private final QuotaGate gate = new QuotaGate(quotaPort);

    private final TenantId tenant = new TenantId("t1");
    private final ModelId model = new ModelId("m1");

    @Test
    void allowed_passesThroughSilently() {
        when(quotaPort.check(any(), any())).thenReturn(new QuotaDecision.Allowed(100L));
        assertThatCode(() -> gate.check(tenant, model, new UsageAtom(1, 100, 50, BigDecimal.ONE)))
                .doesNotThrowAnyException();
    }

    @Test
    void throttled_appliesBackoffAndPasses() {
        when(quotaPort.check(any(), any()))
                .thenReturn(new QuotaDecision.Throttled(30, Duration.ofMinutes(5)));
        // Throttled 不抛（spec §GW-QUOTA-006 放行，应用节流配置）
        assertThatCode(() -> gate.check(tenant, model, new UsageAtom(1, 100, 50, BigDecimal.ONE)))
                .doesNotThrowAnyException();
    }

    @Test
    void suspended_throwsQuotaExceededWithGW4305() {
        when(quotaPort.check(any(), any()))
                .thenReturn(new QuotaDecision.Suspended("rate_exceeded", Instant.now().plusSeconds(60)));
        assertThatThrownBy(() -> gate.check(tenant, model, new UsageAtom(1, 100, 50, BigDecimal.ONE)))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("GW-4305");
    }

    @Test
    void rejected_throwsQuotaExceededWithGW4304() {
        when(quotaPort.check(any(), any()))
                .thenReturn(new QuotaDecision.Rejected("MODEL_TOKEN", 10000L, 12000L));
        assertThatThrownBy(() -> gate.check(tenant, model, new UsageAtom(1, 100, 50, BigDecimal.ONE)))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("GW-4304")
                .hasMessageContaining("MODEL_TOKEN");
    }

    @Test
    void check_passesAllThreeDimensionsToPort() {
        when(quotaPort.check(any(), any())).thenReturn(new QuotaDecision.Allowed(0L));
        gate.check(tenant, model, new UsageAtom(5, 1000, 500, new BigDecimal("1.0")));
        // 三维各查一次（REQUEST / MODEL_TOKEN / MONEY）
        verify(quotaPort).check(eq(new QuotaKey(tenant, model, QuotaDimension.REQUEST)),
                eq(new UsageAtom(5, 0, 0, BigDecimal.ZERO)));
        verify(quotaPort).check(eq(new QuotaKey(tenant, model, QuotaDimension.MODEL_TOKEN)),
                eq(new UsageAtom(0, 1000, 500, BigDecimal.ZERO)));
        verify(quotaPort).check(eq(new QuotaKey(tenant, model, QuotaDimension.MONEY)),
                eq(new UsageAtom(0, 0, 0, new BigDecimal("1.0"))));
        verify(quotaPort, times(3)).check(any(), any());
    }

    @Test
    void check_anyDimensionDenied_shortCircuits() {
        // REQUEST 通过、MODEL_TOKEN 被 Rejected → 整体抛异常（无需查 MONEY）
        when(quotaPort.check(eq(new QuotaKey(tenant, model, QuotaDimension.REQUEST)), any()))
                .thenReturn(new QuotaDecision.Allowed(100L));
        when(quotaPort.check(eq(new QuotaKey(tenant, model, QuotaDimension.MODEL_TOKEN)), any()))
                .thenReturn(new QuotaDecision.Rejected("MODEL_TOKEN", 100, 200));
        assertThatThrownBy(() -> gate.check(tenant, model, new UsageAtom(1, 100, 50, BigDecimal.ONE)))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("GW-4304");
        verify(quotaPort, times(2)).check(any(), any()); // REQUEST + MODEL_TOKEN（未达 MONEY）
    }
}
