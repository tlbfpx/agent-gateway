package com.company.agentgateway.infra.security.observability;

import com.company.agentgateway.domain.iam.RbacDecisionEvent;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class RbacMetricsTest {

    @Test
    void recordAllowed_incrementsRbacAllowedCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        RbacMetrics metrics = new RbacMetrics(registry);
        for (int i = 0; i < 7; i++) {
            metrics.recordAllowed(decision(true));
        }
        assertThat(registry.counter("rbac.allowed",
                "check_point", "rbac_filter",
                "tenant", "t1",
                "user", "u1",
                "agent", "hr-agent",
                "decision", "allowed").count()).isEqualTo(7.0);
    }

    @Test
    void recordDenied_incrementsRbacDeniedCounter_withReason() {
        MeterRegistry registry = new SimpleMeterRegistry();
        RbacMetrics metrics = new RbacMetrics(registry);
        for (int i = 0; i < 3; i++) {
            metrics.recordDenied(decision(false));
        }
        assertThat(registry.counter("rbac.denied",
                "check_point", "rbac_filter",
                "tenant", "t1",
                "user", "u1",
                "agent", "hr-agent",
                "decision", "denied",
                "reason", "no_grant").count()).isEqualTo(3.0);
    }

    @Test
    void previewCheckPoint_isNotRecorded() {
        MeterRegistry registry = new SimpleMeterRegistry();
        RbacMetrics metrics = new RbacMetrics(registry);
        RbacDecisionEvent previewEv = new RbacDecisionEvent(
                "evt", new TenantId("t1"), new UserId("u1"),
                "hr-agent", null,
                RbacDecisionEvent.CheckPoint.PREVIEW,
                RbacDecisionEvent.DecisionReason.NONE,
                true, Instant.now());
        metrics.recordAllowed(previewEv);
        metrics.recordDenied(previewEv);
        // PREVIEW 不上 OTel（spec §GW-RBAC-010）
        assertThat(registry.getMeters()).isEmpty();
    }

    private RbacDecisionEvent decision(boolean allowed) {
        return new RbacDecisionEvent(
                "evt", new TenantId("t1"), new UserId("u1"),
                "hr-agent", new ModelId("qwen"),
                RbacDecisionEvent.CheckPoint.RBAC_FILTER,
                allowed ? RbacDecisionEvent.DecisionReason.NONE : RbacDecisionEvent.DecisionReason.NO_GRANT,
                allowed, Instant.now());
    }
}
