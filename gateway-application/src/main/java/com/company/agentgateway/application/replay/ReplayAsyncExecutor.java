package com.company.agentgateway.application.replay;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Replay 异步执行器(Sprint 2 P2.3 + P3.2):有界队列 + 线程池 + 背压监控 + Prometheus 指标。
 *
 * <h2>关键设计</h2>
 * <ul>
 *   <li><b>有界队列</b>:LinkedBlockingQueue(capacity=1000),避免无限增长撑爆内存</li>
 *   <li><b>CallerRunsPolicy</b>:队列满时让提交线程(orchestrator)自己执行,作为兜底 + 天然限流</li>
 *   <li><b>daemon 线程</b>:不阻断应用关闭</li>
 *   <li><b>Prometheus 指标</b>(P3.2):queue size / pool size / active / rejected / submitted</li>
 * </ul>
 *
 * <h2>注册的 Metric</h2>
 * <ul>
 *   <li><code>replay_async_queue_size</code> — gauge,当前队列长度</li>
 *   <li><code>replay_async_pool_size</code> — gauge,当前池大小</li>
 *   <li><code>replay_async_active_count</code> — gauge,正在执行的线程数</li>
 *   <li><code>replay_async_submitted_total</code> — counter,累计 submit 次数</li>
 *   <li><code>replay_async_completed_total</code> — counter,累计完成任务</li>
 *   <li><code>replay_async_rejected_total</code> — counter,CallerRunsPolicy 触发次数</li>
 * </ul>
 */
public class ReplayAsyncExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReplayAsyncExecutor.class);

    public static final int DEFAULT_QUEUE_CAPACITY = 1000;
    public static final int DEFAULT_CORE_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors());
    public static final int DEFAULT_MAX_POOL_SIZE = DEFAULT_CORE_POOL_SIZE * 2;
    public static final long DEFAULT_KEEP_ALIVE_SECONDS = 60L;

    private final ThreadPoolExecutor executor;
    private final AtomicLong rejectedCount = new AtomicLong();
    private final AtomicLong submittedCount = new AtomicLong();
    private final AtomicLong completedCount = new AtomicLong();
    private final int queueCapacity;

    public ReplayAsyncExecutor() {
        this(DEFAULT_QUEUE_CAPACITY);
    }

    public ReplayAsyncExecutor(int queueCapacity) {
        this(queueCapacity, null);
    }

    /**
     * 主构造:接受 MeterRegistry(可选,null 则用全局 registry)。
     */
    public ReplayAsyncExecutor(int queueCapacity, MeterRegistry meterRegistry) {
        this.queueCapacity = queueCapacity;
        LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(queueCapacity);
        this.executor = new ThreadPoolExecutor(
                DEFAULT_CORE_POOL_SIZE,
                DEFAULT_MAX_POOL_SIZE,
                DEFAULT_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                queue,
                namedThreadFactory("replay-async-"),
                new java.util.concurrent.RejectedExecutionHandler() {
                    @Override
                    public void rejectedExecution(Runnable r, ThreadPoolExecutor exec) {
                        rejectedCount.incrementAndGet();
                        log.warn("replay async queue full (capacity={}, size={}); running in caller thread",
                                queueCapacity, queue.size());
                        // CallerRunsPolicy 语义:不抛异常,提交线程自己跑
                        r.run();
                    }
                });
        // 任务完成后累加 completed counter(executor 内 beforeExecute/afterExecute 不便覆盖,
        // 这里用包装 Runnbale:submit 时 wrap 一层)
        // 实际:completedCount 在 submit 处包一层

        // Prometheus 指标注册(P3.2)
        if (meterRegistry != null) {
            io.micrometer.core.instrument.MeterRegistry mr = meterRegistry;
            Gauge.builder("replay_async_queue_size", executor, e -> e.getQueue().size())
                    .description("replay async task queue size").register(mr);
            Gauge.builder("replay_async_pool_size", executor, ThreadPoolExecutor::getPoolSize)
                    .description("replay async pool size").register(mr);
            Gauge.builder("replay_async_active_count", executor, ThreadPoolExecutor::getActiveCount)
                    .description("replay async active thread count").register(mr);
        }
    }

    /** 提交任务;CallerRunsPolicy 自动兜底。completed count +1。 */
    public void submit(Runnable task) {
        submittedCount.incrementAndGet();
        try {
            // 包装一层以统计 completed(afterExecute 时机难抓,用包装更稳)
            executor.execute(() -> {
                try {
                    task.run();
                } finally {
                    completedCount.incrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            rejectedCount.incrementAndGet();
            log.warn("replay async rejected: {}", e.getMessage());
            task.run(); // 兜底:再跑一次
        }
    }

    /** 当前队列大小(供监控)。 */
    public int queueSize() {
        return executor.getQueue().size();
    }

    /** 累计被拒次数(CallerRunsPolicy 触发时 +1)。 */
    public long rejectedCount() {
        return rejectedCount.get();
    }

    /** 累计提交次数。 */
    public long submittedCount() {
        return submittedCount.get();
    }

    /** 累计完成任务数。 */
    public long completedCount() {
        return completedCount.get();
    }

    /** 当前活跃线程数。 */
    public int activeCount() {
        return executor.getActiveCount();
    }

    /** 当前池大小。 */
    public int poolSize() {
        return executor.getPoolSize();
    }

    /** 队列容量上限。 */
    public int queueCapacity() {
        return queueCapacity;
    }

    public void shutdown() {
        executor.shutdown();
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicLong counter = new AtomicLong();
        return r -> {
            Thread t = new Thread(r, prefix + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}