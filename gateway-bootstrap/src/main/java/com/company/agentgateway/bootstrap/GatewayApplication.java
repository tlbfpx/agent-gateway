package com.company.agentgateway.bootstrap;

import com.company.agentgateway.application.billing.StripeStubAdapter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Agent Gateway 启动入口。
 * 一期本 change 阶段：空 Spring Boot 应用（仅装配骨架）。
 * scanBasePackages 显式排除 domain 包，强化「domain 零框架、无 Spring bean」边界。
 */
@SpringBootApplication(scanBasePackages = {
    "com.company.agentgateway.application",
    "com.company.agentgateway.interfaces",
    "com.company.agentgateway.infra"
})
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    /**
     * Stripe 充值 Stub 适配器（spec §21.7）。
     *
     * <p>同时实现 {@code TopUpPort} 与 {@code VirtualKeyRepository}（委托给内嵌
     * InMemory 仓储），单 bean 即可被 {@code AdminVirtualKeyController} 与
     * {@code StripeWebhookController} 共享。
     */
    @Bean
    public StripeStubAdapter stripeStubAdapter() {
        return new StripeStubAdapter();
    }
}