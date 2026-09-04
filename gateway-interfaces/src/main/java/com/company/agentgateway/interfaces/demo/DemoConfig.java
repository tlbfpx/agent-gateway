package com.company.agentgateway.interfaces.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Demo 模式配置（spec 2026-09-04 §demo-mode §3）。
 *
 * <p>由 {@code gateway.demo.*} 配置驱动；prod 默认关闭，
 * dev/staging 通过 {@code GATEWAY_DEMO_ENABLED=true} 启用。
 *
 * <ul>
 *   <li>{@code enabled} — 总开关；关闭时 {@code /v1/demo/**} 端点全部返回 404</li>
 *   <li>{@code ttl} — demo session 有效期；过期自动清理</li>
 *   <li>{@code cleanup-interval} — 后台清理任务执行间隔</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "gateway.demo")
public class DemoConfig {

    /** 总开关；env {@code GATEWAY_DEMO_ENABLED}。prod 默认 false。 */
    private boolean enabled = false;

    /** demo session 有效期；env {@code GATEWAY_DEMO_TTL}（ISO-8601 duration）。 */
    private Duration ttl = Duration.ofHours(24);

    /** 后台清理任务执行间隔；env {@code GATEWAY_DEMO_CLEANUP_INTERVAL}。 */
    private Duration cleanupInterval = Duration.ofHours(1);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }

    public Duration getCleanupInterval() { return cleanupInterval; }
    public void setCleanupInterval(Duration cleanupInterval) { this.cleanupInterval = cleanupInterval; }
}