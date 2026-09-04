package com.company.agentgateway.interfaces.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Demo 过期清理定时任务（spec 2026-09-04 §demo-mode §5）。
 *
 * <p>默认每 1h 跑一次（可由 {@code gateway.demo.cleanup-interval} 覆盖）；
 * demo 关闭时 {@link DemoService#cleanup()} 直接返回 0，零成本。
 */
@Component
public class DemoCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(DemoCleanupJob.class);

    private final DemoService demoService;

    public DemoCleanupJob(DemoService demoService) {
        this.demoService = demoService;
    }

    /**
     * 每 {@code gateway.demo.cleanup-interval} 跑一次（默认 1h）。
     * fixedDelayString 支持 ISO-8601 duration 与 cron 表达式。
     */
    @Scheduled(fixedDelayString = "${gateway.demo.cleanup-interval:PT1H}",
               initialDelayString = "${gateway.demo.cleanup-interval:PT1H}")
    public void run() {
        try {
            int removed = demoService.cleanup();
            if (removed > 0) {
                log.info("demo.cleanup.removed={}", removed);
            }
        } catch (Exception e) {
            // 清理失败不影响业务；下次再试
            log.warn("demo.cleanup.failed msg={}", e.getMessage());
        }
    }
}