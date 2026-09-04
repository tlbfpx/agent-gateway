package com.company.agentgateway.application.config;

import com.company.agentgateway.application.billing.BillingEngine;
import com.company.agentgateway.application.billing.BudgetGuard;
import com.company.agentgateway.application.billing.UsageWriter;
import com.company.agentgateway.application.quota.QuotaGate;
import com.company.agentgateway.application.quota.QuotedOrchestrator;
import com.company.agentgateway.domain.billing.BillingPort;
import com.company.agentgateway.domain.billing.BudgetRepository;
import com.company.agentgateway.domain.billing.InMemoryBillingRepository;
import com.company.agentgateway.domain.billing.InMemoryBudgetRepository;
import com.company.agentgateway.domain.iam.RbacChangePublisher;
import com.company.agentgateway.domain.quota.InMemoryQuotaRepository;
import com.company.agentgateway.domain.quota.QuotaPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * D2 配额 + 计费装配（spec §21 + design §2.4）。
 *
 * <p>一期默认 InMemory 端口实现；应用服务（QuotaGate / BillingEngine /
 * BudgetGuard / UsageWriter / QuotedOrchestrator）统一在此装配——
 * 依赖缺失时按「可选注入 + 退化」策略，不阻断上下文启动：
 * <ul>
 *   <li>RbacChangePublisher 缺失 → BudgetGuard 用 NOOP publisher（告警仅日志）</li>
 *   <li>ChatOrchestrator 缺失 → QuotedOrchestrator 不装配（纯单测场景）</li>
 * </ul>
 * 二期 JPA/Redis 通过同类型 Bean + {@code @Primary} 替换（design §6 占位清单）。
 */
@AutoConfiguration
public class BillingQuotaAutoConfiguration {

    // ---- 端口（InMemory 一期默认） ----

    @Bean
    @ConditionalOnMissingBean(BillingPort.class)
    public BillingPort billingPort() {
        return new InMemoryBillingRepository();
    }

    @Bean
    @ConditionalOnMissingBean(QuotaPort.class)
    public QuotaPort quotaPort() {
        return new InMemoryQuotaRepository();
    }

    @Bean
    @ConditionalOnMissingBean(BudgetRepository.class)
    public BudgetRepository budgetRepository() {
        return new InMemoryBudgetRepository();
    }

    // ---- 应用服务 ----

    @Bean
    @ConditionalOnMissingBean(BillingEngine.class)
    public BillingEngine billingEngine(BillingPort billingPort) {
        // 单价注册表一期默认零价回退（未知模型不计费，spec §GW-QUOTA-005 失败容错）；
        // 二期接 ModelRegistry.costPer1k{In,Out} 真实单价
        return new BillingEngine(billingPort, model -> null);
    }

    @Bean
    @ConditionalOnMissingBean(BudgetGuard.class)
    public BudgetGuard budgetGuard(BudgetRepository budgetRepository,
                                   @Autowired(required = false) RbacChangePublisher publisher,
                                   @Autowired(required = false)
                                   com.company.agentgateway.domain.observability.AlertStore alertStore,
                                   @Autowired(required = false)
                                   com.company.agentgateway.domain.observability.GatewayEvents events) {
        return new BudgetGuard(budgetRepository, noOpPublisher(publisher), alertStore, events);
    }

    /** 超限降级策略（P1）：供 ChatOrchestrator 装配（DOWNGRADE 时降级到 fallbackModel）。 */
    @Bean
    @ConditionalOnMissingBean(com.company.agentgateway.application.billing.BudgetDowngradePolicy.class)
    public com.company.agentgateway.application.billing.BudgetDowngradePolicy budgetDowngradePolicy(
            BudgetRepository budgetRepository) {
        return new com.company.agentgateway.application.billing.BudgetDowngradePolicy(budgetRepository);
    }

    @Bean
    @ConditionalOnMissingBean(UsageWriter.class)
    public UsageWriter usageWriter(BillingPort billingPort) {
        UsageWriter writer = new UsageWriter(billingPort);
        writer.start();
        return writer;
    }

    @Bean
    @ConditionalOnMissingBean(QuotaGate.class)
    public QuotaGate quotaGate(QuotaPort quotaPort) {
        return new QuotaGate(quotaPort);
    }

    @Bean
    @ConditionalOnBean(name = "chatOrchestrator")
    @ConditionalOnMissingBean(QuotedOrchestrator.class)
    public QuotedOrchestrator quotedOrchestrator(
            org.springframework.context.ApplicationContext ctx, QuotaGate quotaGate) {
        return new QuotedOrchestrator(
                ctx.getBean(com.company.agentgateway.application.orchestration.ChatOrchestrator.class),
                quotaGate);
    }

    /** publisher 缺失时的 NOOP 降级（事件黑洞 + 不抛）。 */
    private static RbacChangePublisher noOpPublisher(RbacChangePublisher delegate) {
        if (delegate != null) return delegate;
        return event -> {
            org.slf4j.LoggerFactory.getLogger(BillingQuotaAutoConfiguration.class)
                    .warn("RbacChangePublisher absent, budget alert dropped: {}", event);
            return subscriber -> { }; // 空 Publisher：订阅即静默
        };
    }
}
