package com.company.agentgateway.spike;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * 0 阶段 Spike：验证 Spring Boot 4.0 + SAA 2.0.0-M1.1 + JDK 21 能否启动并装配 dashscope autoconfig。
 * 用 CommandLineRunner 探测真实装配的 ChatModel bean，证明 starter 真正生效（而非被 @ConditionalOnClass 跳过）。
 */
@SpringBootApplication(scanBasePackages = "com.company.agentgateway.spike")
public class CompatCheck {
    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(CompatCheck.class, args)));
    }

    @Bean
    CommandLineRunner probe(ApplicationContext ctx) {
        return args -> {
            long dashscopeBeans = java.util.Arrays.stream(ctx.getBeanDefinitionNames())
                .filter(n -> n.toLowerCase().contains("dashscope") || n.toLowerCase().contains("chatmodel"))
                .count();
            System.out.println("SPIKE_OK: beanCount=" + ctx.getBeanDefinitionCount()
                + " dashscopeOrChatModelBeans=" + dashscopeBeans);
            if (dashscopeBeans == 0) {
                System.out.println("SPIKE_WARN: no dashscope/ChatModel bean found — starter may have been skipped");
            }
        };
    }
}
