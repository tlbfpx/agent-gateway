package com.company.agentgateway.domain.billing;

import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;

/**
 * BillingPort Contract Test（spec §21.6 + D2 GW-QUOTA-002）。
 *
 * 验证所有实现都必须满足：4 端口方法 + 租户隔离 + 用量与成本计算。
 */
class BillingPortContractTest {

    /** 测试用 InMemory 桩（验证零实现可编译）。 */
    static class InMemoryStub implements BillingPort {
        final java.util.Map<TenantId, List<UsageRecord>> store = new ConcurrentHashMap<>();

        @Override
        public void recordUsage(UsageRecord record) {
            store.computeIfAbsent(record.tenant(), k -> new CopyOnWriteArrayList<>()).add(record);
        }

        @Override
        public List<UsageRecord> queryUsage(UsageQuery q) {
            List<UsageRecord> all = store.getOrDefault(q.tenant(), List.of());
            return all.stream()
                    .filter(r -> q.model() == null || r.model().equals(q.model()))
                    .filter(r -> q.agentName() == null || q.agentName().equals(r.agentName()))
                    .filter(r -> q.from() == null || !r.timestamp().isBefore(q.from()))
                    .filter(r -> q.to() == null || !r.timestamp().isAfter(q.to()))
                    .toList();
        }

        @Override
        public BigDecimal queryCost(UsageQuery q) {
            return queryUsage(q).stream()
                    .map(UsageRecord::cost)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        @Override
        public List<UsageRecord> exportUsage(UsageQuery q, ExportFormat format) {
            return queryUsage(q);
        }
    }

    private UsageRecord rec(String id, TenantId t, ModelId m, Instant ts, String cost) {
        return new UsageRecord(id, t, new UserId("u1"), m, "agent", ts, 100, 50,
                new BigDecimal(cost), BigDecimal.ONE, BigDecimal.ONE);
    }

    @Test
    void recordAndQuery_sameTenant_returnsSameRecord() {
        BillingPort port = new InMemoryStub();
        TenantId t = new TenantId("t1");
        UsageRecord r = rec("r1", t, new ModelId("m1"), Instant.now(), "1");
        port.recordUsage(r);
        assertThat(port.queryUsage(new UsageQuery(t, null, null, null, null))).containsExactly(r);
    }

    @Test
    void tenantIsolation_diffTenant_notVisible() {
        BillingPort port = new InMemoryStub();
        port.recordUsage(rec("r1", new TenantId("t1"), new ModelId("m1"), Instant.now(), "1"));
        assertThat(port.queryUsage(new UsageQuery(new TenantId("t2"), null, null, null, null))).isEmpty();
    }

    @Test
    void queryUsage_filtersByModelAndDateRange() {
        BillingPort port = new InMemoryStub();
        TenantId t = new TenantId("t1");
        Instant t1 = Instant.parse("2026-08-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-08-15T00:00:00Z");
        Instant t3 = Instant.parse("2026-09-01T00:00:00Z");
        port.recordUsage(rec("r1", t, new ModelId("m1"), t1, "1"));
        port.recordUsage(rec("r2", t, new ModelId("m1"), t2, "1"));
        port.recordUsage(rec("r3", t, new ModelId("m2"), t3, "1"));
        // model=m1 + 时间范围 [t1, t2] → r1 + r2
        List<UsageRecord> got = port.queryUsage(new UsageQuery(t, t1, t2, new ModelId("m1"), null));
        assertThat(got).hasSize(2);
        assertThat(got.stream().map(UsageRecord::recordId)).containsExactlyInAnyOrder("r1", "r2");
    }

    @Test
    void queryCost_sumsAllMatchingRecords() {
        BillingPort port = new InMemoryStub();
        TenantId t = new TenantId("t1");
        port.recordUsage(rec("r1", t, new ModelId("m1"), Instant.now(), "1.50"));
        port.recordUsage(rec("r2", t, new ModelId("m1"), Instant.now(), "2.50"));
        BigDecimal cost = port.queryCost(new UsageQuery(t, null, null, null, null));
        assertThat(cost).isEqualByComparingTo(new BigDecimal("4.00"));
    }

    @Test
    void exportUsage_returnsRecords_forGivenFormat() {
        BillingPort port = new InMemoryStub();
        TenantId t = new TenantId("t1");
        port.recordUsage(rec("r1", t, new ModelId("m1"), Instant.now(), "1"));
        assertThat(port.exportUsage(new UsageQuery(t, null, null, null, null), ExportFormat.CSV)).hasSize(1);
    }

    @Test
    void inMemoryRepository_passesSameContract() {
        BillingPort port = new InMemoryBillingRepository();
        TenantId t = new TenantId("t1");
        port.recordUsage(rec("r1", t, new ModelId("m1"), Instant.now(), "1"));
        port.recordUsage(rec("r2", new TenantId("t2"), new ModelId("m1"), Instant.now(), "1"));
        assertThat(port.queryUsage(new UsageQuery(t, null, null, null, null))).hasSize(1);
        assertThat(port.queryCost(new UsageQuery(t, null, null, null, null))).isEqualByComparingTo("1");
    }
}
