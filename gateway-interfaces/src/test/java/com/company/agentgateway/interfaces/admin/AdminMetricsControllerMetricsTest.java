package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.billing.BillingPort;
import com.company.agentgateway.domain.billing.UsageRecord;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D2 GW-QUOTA-009：AdminMetricsController 替换 1500 硬编码 — 真实计费数据断言。
 */
class AdminMetricsControllerMetricsTest {

    private AuditRepository.AuditLog log(String modelId) {
        return new AuditRepository.AuditLog(
                "e1", new TenantId("t1"), "alice", AuditRepository.AuditLog.ActorType.HUMAN,
                AuditRepository.AuditEventType.SESSION_CHAT, Instant.now(),
                "model", modelId, "chat",
                AuditRepository.AuditLog.Result.SUCCESS, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cost_usesRealBillingTokens_notHardcoded1500() {
        AuditRepository audit = mock(AuditRepository.class);
        BillingPort billing = mock(BillingPort.class);
        // 1 条 m1 记账：tokensIn=123 + tokensOut=45 = 168 ≠ 1500
        when(billing.queryUsage(any())).thenReturn(List.of(
                new UsageRecord("r1", new TenantId("t1"), new UserId("u1"),
                        new ModelId("m1"), "agent", Instant.now(),
                        123, 45, new BigDecimal("0.50"), BigDecimal.ONE, BigDecimal.ONE)));
        when(audit.query(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(log("m1")));
        AdminMetricsController controller = new AdminMetricsController(audit, billing);

        Map<String, Object> r = controller.cost("k", "t1", "24h");
        Map<String, Object> total = (Map<String, Object>) r.get("total");
        assertThat(total.get("tokens")).isEqualTo(168L);      // 真实 token，非 1500
        assertThat(total.get("costCny")).isEqualTo(0.5);      // 真实成本快照
    }

    @Test
    @SuppressWarnings("unchecked")
    void cost_noBillingPort_fallsBackToEstimate1500() {
        AuditRepository audit = mock(AuditRepository.class);
        when(audit.query(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(log("m1")));
        AdminMetricsController controller = new AdminMetricsController(audit);

        Map<String, Object> r = controller.cost("k", "t1", "24h");
        Map<String, Object> total = (Map<String, Object>) r.get("total");
        assertThat(total.get("tokens")).isEqualTo(1500L);     // 降级口径保持
    }

    @Test
    @SuppressWarnings("unchecked")
    void cost_billingFailure_degradesToEstimateWithoutError() {
        AuditRepository audit = mock(AuditRepository.class);
        BillingPort billing = mock(BillingPort.class);
        when(billing.queryUsage(any())).thenThrow(new RuntimeException("billing down"));
        when(audit.query(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(log("m1")));
        AdminMetricsController controller = new AdminMetricsController(audit, billing);

        Map<String, Object> r = controller.cost("k", "t1", "24h");
        Map<String, Object> total = (Map<String, Object>) r.get("total");
        assertThat(total.get("tokens")).isEqualTo(1500L);     // 容错降级，不抛
    }
}
