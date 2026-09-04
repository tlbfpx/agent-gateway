package com.company.agentgateway.domain.observability;

import java.util.Map;

/**
 * 出站事件端口（spec §25 事件的 domain 抽象）。
 * 编排器发布；infra/桥接到 Webhook、审计告警等。默认 NOOP。
 */
public interface GatewayEvents {

    void publish(String eventType, Map<String, Object> payload);

    GatewayEvents NOOP = (t, p) -> {};
}
