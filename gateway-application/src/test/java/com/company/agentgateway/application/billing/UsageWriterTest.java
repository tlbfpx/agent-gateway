package com.company.agentgateway.application.billing;

import com.company.agentgateway.domain.billing.BillingPort;
import com.company.agentgateway.domain.billing.UsageRecord;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UsageWriterTest {

    private UsageRecord rec(String id) {
        return new UsageRecord(id, new TenantId("t1"), new UserId("u1"),
                new ModelId("m1"), "agent", Instant.now(), 1, 1,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
    }

    @Test
    void enqueue_isAsynchronous_doesNotBlockCaller() throws InterruptedException {
        CountDownLatch recorded = new CountDownLatch(1);
        AtomicReference<UsageRecord> captured = new AtomicReference<>();
        BillingPort port = mock(BillingPort.class);
        doAnswer(inv -> { captured.set(inv.getArgument(0)); recorded.countDown(); return null; })
                .when(port).recordUsage(any());
        UsageWriter writer = new UsageWriter(port);
        writer.start();

        long start = System.currentTimeMillis();
        writer.enqueue(rec("r1"));
        long elapsed = System.currentTimeMillis() - start;

        // 入队耗时 < 50ms（不阻塞主调用链，spec §21.3 异步原则）
        assertThat(elapsed).isLessThan(50L);
        // 异步落账最终发生
        assertThat(recorded.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.get().recordId()).isEqualTo("r1");
        writer.shutdown();
    }

    @Test
    void enqueue_recordingFailure_doesNotKillWorker() {
        BillingPort port = mock(BillingPort.class);
        doThrow(new RuntimeException("redis down")).when(port).recordUsage(any());
        UsageWriter writer = new UsageWriter(port);
        writer.start();
        // 连续失败多次，不抛（drainer 内部吞咽）
        for (int i = 0; i < 3; i++) {
            final String id = "r" + i;
            assertThatCode(() -> writer.enqueue(rec(id))).doesNotThrowAnyException();
        }
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        writer.shutdown();
    }

    @Test
    void shutdown_drainsRemainingQueue() {
        BillingPort port = mock(BillingPort.class);
        AtomicInteger count = new AtomicInteger();
        doAnswer(inv -> { count.incrementAndGet(); return null; }).when(port).recordUsage(any());
        UsageWriter writer = new UsageWriter(port);
        writer.start();
        for (int i = 0; i < 5; i++) {
            writer.enqueue(rec("r" + i));
        }
        writer.shutdown();
        // drain 应全部落账（至少 5 次）
        verify(port, atLeast(5)).recordUsage(any());
    }
}
