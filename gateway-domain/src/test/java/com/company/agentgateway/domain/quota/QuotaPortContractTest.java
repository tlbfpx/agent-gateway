package com.company.agentgateway.domain.quota;

import com.company.agentgateway.domain.billing.UsageAtom;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/**
 * QuotaPort Contract Test（spec §16.2 + D2 GW-QUOTA-002）。
 *
 * 4 端口方法 + 租户隔离 + check/consume/reverse 三步一致。
 * 直接验证 InMemoryQuotaRepository（一期默认实现）。
 */
class QuotaPortContractTest {

    private final QuotaPort port = new InMemoryQuotaRepository();

    private QuotaKey key(TenantId t) {
        return new QuotaKey(t, new ModelId("m1"), QuotaDimension.MODEL_TOKEN);
    }

    @Test
    void check_belowLimit_returnsAllowedWithRemaining() {
        TenantId t = new TenantId("t1");
        QuotaDecision d = port.check(key(t), new UsageAtom(1, 100, 50, BigDecimal.ONE));
        assertThat(d).isInstanceOf(QuotaDecision.Allowed.class);
        assertThat(((QuotaDecision.Allowed) d).remaining()).isEqualTo(9900L);
    }

    @Test
    void check_exceedsLimit_returnsRejected() {
        TenantId t = new TenantId("t1");
        // 一次性消耗 12000 > limit 10000 → rejected
        QuotaDecision d = port.check(key(t), new UsageAtom(1, 12000, 0, BigDecimal.ONE));
        assertThat(d).isInstanceOf(QuotaDecision.Rejected.class);
        QuotaDecision.Rejected r = (QuotaDecision.Rejected) d;
        assertThat(r.quotaDimension()).isEqualTo("MODEL_TOKEN");
        assertThat(r.limit()).isEqualTo(10000L);
        assertThat(r.used()).isEqualTo(12000L);
    }

    @Test
    void consumeThenCheck_countsTowardsLimit() {
        TenantId t = new TenantId("t1");
        port.consume(key(t), new UsageAtom(1, 9000, 0, BigDecimal.ONE));
        // 再请求 2000 → 累计 11000 > limit 10000 → rejected
        QuotaDecision d = port.check(key(t), new UsageAtom(1, 2000, 0, BigDecimal.ONE));
        assertThat(d).isInstanceOf(QuotaDecision.Rejected.class);
    }

    @Test
    void reverse_unconsumesForFailedCalls() {
        TenantId t = new TenantId("t1");
        port.consume(key(t), new UsageAtom(1, 5000, 0, BigDecimal.ONE));
        // 失败回滚
        port.reverse(key(t), new UsageAtom(1, 5000, 0, BigDecimal.ONE));
        // 重新请求 4000 → 累计 4000 ≤ limit 10000 → allowed
        QuotaDecision d = port.check(key(t), new UsageAtom(1, 4000, 0, BigDecimal.ONE));
        assertThat(d).isInstanceOf(QuotaDecision.Allowed.class);
    }

    @Test
    void snapshot_returnsDecisionsForTenant() {
        TenantId t = new TenantId("t1");
        port.consume(key(t), new UsageAtom(1, 3000, 0, BigDecimal.ONE));
        List<QuotaDecision> snap = port.snapshot(t);
        assertThat(snap).hasSize(1);
        assertThat(((QuotaDecision.Allowed) snap.get(0)).remaining()).isEqualTo(7000L);
    }

    @Test
    void tenantIsolation_diffTenantIndependentCounters() {
        TenantId t1 = new TenantId("t1");
        TenantId t2 = new TenantId("t2");
        port.consume(key(t1), new UsageAtom(1, 9000, 0, BigDecimal.ONE));
        // t2 不受 t1 影响
        assertThat(((QuotaDecision.Allowed) port.check(key(t2),
                new UsageAtom(1, 100, 0, BigDecimal.ONE))).remaining()).isEqualTo(9900L);
    }
}
