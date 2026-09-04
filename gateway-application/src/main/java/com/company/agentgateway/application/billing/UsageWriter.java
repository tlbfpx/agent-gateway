package com.company.agentgateway.application.billing;

import com.company.agentgateway.domain.billing.BillingPort;
import com.company.agentgateway.domain.billing.UsageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 异步用量写入器（spec §21.3 + D2 决策点 D-2）。
 *
 * <p>一期实现：ArrayBlockingQueue + 单线程 drainer（与 D1 NacosRbacChangePublisher 占位策略一致）。
 * 二期替换为 RabbitMQ/Kafka Producer，drop-in 替换 drainer 即可。
 *
 * <p>失败容错：入队满则丢弃 + log warn（不阻塞主调用链）；drain 异常吞咽。
 */
public class UsageWriter implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(UsageWriter.class);

    private static final int QUEUE_CAPACITY = 10_000;
    private static final int DRAINER_SHUTDOWN_TIMEOUT_MS = 5_000;

    private final BillingPort billingPort;
    private final BlockingQueue<UsageRecord> queue;
    private Thread drainer;
    private volatile boolean running = true;

    public UsageWriter(BillingPort billingPort) {
        this(billingPort, new ArrayBlockingQueue<>(QUEUE_CAPACITY));
    }

    /** 测试用构造函数：可注入自定义 queue。 */
    UsageWriter(BillingPort billingPort, BlockingQueue<UsageRecord> queue) {
        this.billingPort = billingPort;
        this.queue = queue;
    }

    /** 显式启动 drainer（Spring Bean 初始化后由 bootstrap 调用；测试直接调用）。 */
    public void start() {
        if (drainer != null && drainer.isAlive()) return; // 幂等
        running = true;
        drainer = new Thread(this::drainLoop, "usage-writer-drainer");
        drainer.setDaemon(true);
        drainer.start();
    }

    @Override
    public void destroy() {
        shutdown();
    }

    public void shutdown() {
        running = false;
        if (drainer != null) {
            try {
                drainer.join(DRAINER_SHUTDOWN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 非阻塞入队（满则丢弃 + log warn，避免阻塞主调用链）。 */
    public void enqueue(UsageRecord record) {
        if (!queue.offer(record)) {
            log.warn("UsageWriter queue full, dropping record {}", record.recordId());
        }
    }

    private void drainLoop() {
        // 关停语义：running=false 后继续排空队列剩余记录再退出（graceful drain）
        while (running || !queue.isEmpty()) {
            try {
                UsageRecord record = queue.poll(200, TimeUnit.MILLISECONDS);
                if (record != null) {
                    billingPort.recordUsage(record);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!running && queue.isEmpty()) {
                    return;
                }
            } catch (Exception e) {
                log.warn("UsageWriter drain failed (swallowed): {}", e.getMessage());
            }
        }
    }
}
