package com.company.agentgateway.application.billing;

import com.company.agentgateway.domain.billing.BillingPort;
import com.company.agentgateway.domain.billing.UsageQuery;
import com.company.agentgateway.domain.billing.UsageRecord;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BillingEngineTest {

    @Test
    void recordUsage_snapshotsUnitPriceFromModelRegistry() {
        BillingPort port = mock(BillingPort.class);
        AtomicReference<UsageRecord> captured = new AtomicReference<>();
        doAnswer(inv -> { captured.set(inv.getArgument(0)); return null; }).when(port).recordUsage(any());
        // qwen: 0.0001 in / 0.0003 out per token
        BillingEngine engine = new BillingEngine(port,
                modelId -> new ModelId.Price(new BigDecimal("0.0001"), new BigDecimal("0.0003")));
        TenantId t = new TenantId("t1");

        engine.recordUsage(t, new UserId("u1"), new ModelId("qwen"), "agent", 1000L, 500L);

        assertThat(captured.get()).isNotNull();
        // cost = 1000 * 0.0001 + 500 * 0.0003 = 0.10 + 0.15 = 0.25
        assertThat(captured.get().cost()).isEqualByComparingTo(new BigDecimal("0.25"));
        assertThat(captured.get().unitPriceIn()).isEqualByComparingTo("0.0001");
        assertThat(captured.get().unitPriceOut()).isEqualByComparingTo("0.0003");
        verify(port, times(1)).recordUsage(any());
    }

    @Test
    void recordUsage_unknownModel_usesZeroPriceFallback() {
        BillingPort port = mock(BillingPort.class);
        AtomicReference<UsageRecord> captured = new AtomicReference<>();
        doAnswer(inv -> { captured.set(inv.getArgument(0)); return null; }).when(port).recordUsage(any());
        BillingEngine engine = new BillingEngine(port, modelId -> null); // 未知 model
        engine.recordUsage(new TenantId("t1"), new UserId("u1"), new ModelId("unknown-model"), "agent", 100, 50);
        assertThat(captured.get().cost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(captured.get().unitPriceIn()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void recordUsage_negativeTokens_treatedAsZero() {
        BillingPort port = mock(BillingPort.class);
        AtomicReference<UsageRecord> captured = new AtomicReference<>();
        doAnswer(inv -> { captured.set(inv.getArgument(0)); return null; }).when(port).recordUsage(any());
        BillingEngine engine = new BillingEngine(port,
                modelId -> new ModelId.Price(new BigDecimal("0.0001"), new BigDecimal("0.0003")));
        engine.recordUsage(new TenantId("t1"), new UserId("u1"), new ModelId("qwen"), "agent", -1L, -2L);
        // 防御式：负数视为 0（外部观测可能传错）
        assertThat(captured.get().tokensIn()).isEqualTo(0L);
        assertThat(captured.get().tokensOut()).isEqualTo(0L);
    }

    @Test
    void recordUsage_portFailure_doesNotPropagate() {
        BillingPort port = mock(BillingPort.class);
        doThrow(new RuntimeException("redis down")).when(port).recordUsage(any());
        BillingEngine engine = new BillingEngine(port, id -> null);
        // spec §GW-QUOTA-005 失败容错
        assertThatCode(() -> engine.recordUsage(new TenantId("t1"), new UserId("u1"),
                new ModelId("m1"), "agent", 100, 50)).doesNotThrowAnyException();
    }

    @Test
    void totalCost_delegatesToPort() {
        BillingPort port = mock(BillingPort.class);
        when(port.queryCost(any())).thenReturn(new BigDecimal("1.23"));
        BillingEngine engine = new BillingEngine(port, id -> null);
        BigDecimal total = engine.totalCost(new UsageQuery(new TenantId("t1"), null, null, null, null));
        assertThat(total).isEqualByComparingTo("1.23");
        verify(port).queryCost(any());
    }
}
