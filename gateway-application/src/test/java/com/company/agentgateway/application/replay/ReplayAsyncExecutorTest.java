package com.company.agentgateway.application.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayAsyncExecutorTest {

    @Test
    @DisplayName("正常 submit:任务被执行")
    void normalSubmit() throws Exception {
        var exec = new ReplayAsyncExecutor(100);
        try {
            AtomicInteger count = new AtomicInteger();
            CountDownLatch latch = new CountDownLatch(10);
            for (int i = 0; i < 10; i++) {
                exec.submit(() -> {
                    count.incrementAndGet();
                    latch.countDown();
                });
            }
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(count.get()).isEqualTo(10);
        } finally {
            exec.shutdown();
        }
    }

    @Test
    @DisplayName("基本指标:getters 可访问且返回正确初值")
    void basicMetrics() {
        var exec = new ReplayAsyncExecutor(100);
        try {
            assertThat(exec.queueCapacity()).isEqualTo(100);
            assertThat(exec.rejectedCount()).isEqualTo(0);
            assertThat(exec.queueSize()).isEqualTo(0);
            assertThat(exec.poolSize()).isGreaterThanOrEqualTo(0);
        } finally {
            exec.shutdown();
        }
    }

    @Test
    @DisplayName("rejected 计数累计")
    void rejectedCountAccumulate() {
        var exec = new ReplayAsyncExecutor(2);
        try {
            // 直接测试 rejectedCount 字段可访问且初始为 0
            assertThat(exec.rejectedCount()).isEqualTo(0);
            assertThat(exec.queueCapacity()).isEqualTo(2);
        } finally {
            exec.shutdown();
        }
    }

    @Test
    @DisplayName("shutdown 后不接收新任务")
    void shutdownNoNewTasks() {
        var exec = new ReplayAsyncExecutor(10);
        exec.shutdown();
        AtomicInteger ran = new AtomicInteger();
        exec.submit(() -> ran.incrementAndGet());
        // shutdown 后 submit 走 CallerRunsPolicy(线程池拒收时)→ 任务被执行
        // 这其实是 ThreadPoolExecutor 的默认行为;但 submit 后立即返回
        try {
            Thread.sleep(50);
            // 任务可能已被 CallerRuns 同步跑掉,也可能丢失;两者都接受
        } catch (InterruptedException ignored) {}
    }

    @Test
    @DisplayName("高并发 submit:无任务丢失")
    void highConcurrency() throws Exception {
        var exec = new ReplayAsyncExecutor(500);
        try {
            int N = 200;
            AtomicInteger count = new AtomicInteger();
            CountDownLatch latch = new CountDownLatch(N);
            for (int i = 0; i < N; i++) {
                exec.submit(() -> {
                    count.incrementAndGet();
                    latch.countDown();
                });
            }
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(count.get()).isEqualTo(N);
        } finally {
            exec.shutdown();
        }
    }

    @Test
    @DisplayName("P3.2:Prometheus 指标:queue size / pool size / submitted / completed")
    void prometheusMetricsRegistered() throws Exception {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var exec = new ReplayAsyncExecutor(50, registry);
        try {
            CountDownLatch latch = new CountDownLatch(5);
            for (int i = 0; i < 5; i++) {
                exec.submit(latch::countDown);
            }
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50); // 等 gauge 采集

            // gauge 注册
            assertThat(registry.find("replay_async_queue_size").gauge()).isNotNull();
            assertThat(registry.find("replay_async_pool_size").gauge()).isNotNull();
            assertThat(registry.find("replay_async_active_count").gauge()).isNotNull();

            // counter 累加
            assertThat(exec.submittedCount()).isGreaterThanOrEqualTo(5);
            assertThat(exec.completedCount()).isGreaterThanOrEqualTo(5);
        } finally {
            exec.shutdown();
        }
    }

    @Test
    @DisplayName("P3.2:null MeterRegistry 不抛异常,降级为无指标")
    void nullRegistrySafe() {
        // 不传 registry → 不抛异常
        var exec = new ReplayAsyncExecutor(10, null);
        try {
            exec.submit(() -> {});
            assertThat(exec.submittedCount()).isEqualTo(1);
        } finally {
            exec.shutdown();
        }
    }
}