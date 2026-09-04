package com.company.agentgateway.interfaces.webhook;

import com.company.agentgateway.domain.observability.GatewayEvents;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 桥接：domain GatewayEvents 端口 → WebhookDispatcher 投递。 */
@Configuration
public class WebhookEventBridge {

    @Bean
    public GatewayEvents gatewayEvents(WebhookDispatcher dispatcher) {
        return dispatcher::publish;
    }
}
