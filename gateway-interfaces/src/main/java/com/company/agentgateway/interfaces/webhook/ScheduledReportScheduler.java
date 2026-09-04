package com.company.agentgateway.interfaces.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.Map;

/**
 * 定时报表调度器（spec §25.4）。
 *
 * <p>单线程每 60s 扫描全部订阅，把 {@code nextFireAt <= now} 的报表通过
 * {@link WebhookDispatcher} 推送出去（事件名 {@code cost.report.<period>}），
 * 随后按周期推进 {@code nextFireAt}。
 *
 * <p><b>失败语义</b>：单条投递异常只 log warn，不中断本轮扫描的其他订阅；且失败也会推进
 * {@code nextFireAt}，避免下一 tick 立刻重复轰炸同一个坏地址（真正的重试/死信由
 * {@link WebhookDispatcher} 内部的指数退避 + DLQ 负责）。
 */
public class ScheduledReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScheduledReportScheduler.class);

    private final WebhookDispatcher dispatcher;
    private final ScheduledReportRepository repository;
    private final ReportFormatter formatter;

    public ScheduledReportScheduler(WebhookDispatcher dispatcher,
                                    ScheduledReportRepository repository,
                                    ReportFormatter formatter) {
        this.dispatcher = dispatcher;
        this.repository = repository;
        this.formatter = formatter;
    }

    /** 每 60s 扫描一次到期订阅（fixedDelay：上一轮结束后再计时，天然串行）。 */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void tick() {
        Instant now = Instant.now();
        for (ScheduledReport report : repository.findAll()) {
            if (report.nextFireAt().isAfter(now)) continue;
            fire(report);
            // 推进排期：即使投递失败也推进，避免重复轰炸
            repository.findById(report.reportId())
                    .ifPresent(current -> repository.save(current.advanced()));
        }
    }

    /**
     * 立即触发一次（管理台「测试」按钮）：跳过 {@code nextFireAt} 到期检查，
     * 且不改变既有排期。
     *
     * @return 订阅存在并已尝试投递返回 {@code true}；找不到订阅返回 {@code false}
     */
    public boolean fireNow(String reportId) {
        return repository.findById(reportId).map(report -> {
            fire(report);
            return true;
        }).orElse(false);
    }

    /** 单条投递（异常吞掉只 log，保证调度循环不被打断）。 */
    private void fire(ScheduledReport report) {
        try {
            Map<String, Object> payload = formatter.buildPayload(report);
            dispatcher.publish(report.eventType(), payload);
            log.info("scheduled report {} published as {} ({} rows)",
                    report.reportId(), report.eventType(), payload.get("rows"));
        } catch (Exception e) {
            log.warn("scheduled report {} failed: {}", report.reportId(), e.getMessage());
        }
    }
}
