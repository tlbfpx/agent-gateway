package com.company.agentgateway.infra.security.safety;

import com.company.agentgateway.domain.safety.GuardrailFacade;
import com.company.agentgateway.domain.safety.GuardrailPolicy;
import com.company.agentgateway.domain.safety.GuardrailPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Guardrail 自动装配(Round 9):
 * 提供 DefaultGuardrailService + GuardrailFacade 两个 bean。
 *
 * <p>{@code gateway.guardrails.enabled=false} 时不装配,facade 由调用方延迟装配。
 */
@Configuration
@ConditionalOnProperty(name = "gateway.guardrails.enabled", havingValue = "true", matchIfMissing = true)
public class GuardrailAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GuardrailAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(GuardrailPort.class)
    public DefaultGuardrailService defaultGuardrailService() {
        log.info("Guardrail:DefaultGuardrailService 初始化(默认安全策略 BLOCK)");
        return new DefaultGuardrailService(GuardrailPolicy.defaultSafe());
    }

    @Bean
    @ConditionalOnMissingBean(GuardrailFacade.class)
    public GuardrailFacade guardrailFacade(DefaultGuardrailService port) {
        // 默认 sink:仅打日志(audit + metrics 接入留 Round 9 后续)
        return new GuardrailFacade(port, port.currentPolicy(), violation -> {
            if (log.isWarnEnabled()) {
                log.warn("guardrail violation: rule={} tenant={} action={} matched={}",
                        violation.rule(), violation.tenant(), violation.action(), violation.matchedText());
            }
        });
    }
}