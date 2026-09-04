package com.company.agentgateway.interfaces.webhook;

import com.company.agentgateway.domain.billing.BillingPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Webhook 装配 + 定时报表调度装配（spec §25.4）。
 *
 * <p>{@code @EnableScheduling} 放在本配置类而非 bootstrap 应用类，
 * 让调度能力随 webhook 模块一起装配，不污染启动类。
 */
@Configuration
@EnableScheduling
public class AdminWebhookConfig {

    @Bean
    public WebhookDispatcher webhookDispatcher() {
        return new WebhookDispatcher();
    }

    @Bean
    public ScheduledReportRepository scheduledReportRepository() {
        return new ScheduledReportRepository();
    }

    @Bean
    public ReportFormatter reportFormatter(BillingPort billingPort) {
        return new ReportFormatter(billingPort);
    }

    @Bean
    public ScheduledReportScheduler scheduledReportScheduler(WebhookDispatcher dispatcher,
                                                             ScheduledReportRepository repository,
                                                             ReportFormatter formatter) {
        return new ScheduledReportScheduler(dispatcher, repository, formatter);
    }
}
