package com.company.agentgateway.domain.quota;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class QuotaDecisionTest {

    @Test
    void allowed_carriesRemaining() {
        QuotaDecision.Allowed a = new QuotaDecision.Allowed(100L);
        assertThat(a.remaining()).isEqualTo(100L);
    }

    @Test
    void throttled_carriesNewQpsAndDuration() {
        QuotaDecision.Throttled t = new QuotaDecision.Throttled(30, Duration.ofMinutes(5));
        assertThat(t.newQpsPercent()).isEqualTo(30);
        assertThat(t.duration()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void suspended_carriesReasonAndUntilAt() {
        Instant until = Instant.now().plusSeconds(300);
        QuotaDecision.Suspended s = new QuotaDecision.Suspended("quota_exceeded", until);
        assertThat(s.reason()).isEqualTo("quota_exceeded");
        assertThat(s.untilAt()).isEqualTo(until);
    }

    @Test
    void rejected_carriesDimensionLimitAndUsed() {
        QuotaDecision.Rejected r = new QuotaDecision.Rejected("MODEL_TOKEN", 10000L, 12000L);
        assertThat(r.quotaDimension()).isEqualTo("MODEL_TOKEN");
        assertThat(r.limit()).isEqualTo(10000L);
        assertThat(r.used()).isEqualTo(12000L);
    }

    @Test
    void sealedExhaustiveness_patternMatching_compiles() {
        // Java 21 sealed 强制 exhaustiveness：4 个分支全编译
        QuotaDecision d = new QuotaDecision.Allowed(50L);
        String result = switch (d) {
            case QuotaDecision.Allowed a -> "allowed:" + a.remaining();
            case QuotaDecision.Throttled t -> "throttled:" + t.newQpsPercent();
            case QuotaDecision.Suspended s -> "suspended:" + s.reason();
            case QuotaDecision.Rejected r -> "rejected:" + r.quotaDimension();
        };
        assertThat(result).isEqualTo("allowed:50");
    }
}
