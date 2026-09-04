package com.company.agentgateway.interfaces.demo;

import java.time.Instant;

/**
 * Demo session 凭据（spec 2026-09-04 §demo-mode §4）。
 *
 * <p>返回给前端 bootstrap 调用方：
 * <ul>
 *   <li>{@code tenantId} — 写 {@code localStorage.agent-gateway.tenant}</li>
 *   <li>{@code apiKey} — 写 {@code localStorage.agent-gateway.apiKey}</li>
 *   <li>{@code adminToken} — 写 {@code localStorage.agent-gateway.adminToken}</li>
 *   <li>{@code adminEmail} — 仅展示，不写 localStorage</li>
 *   <li>{@code expiresAt} — DemoBanner 倒计时依据</li>
 * </ul>
 */
public record DemoSession(
        String tenantId,
        String apiKey,
        String adminToken,
        String adminEmail,
        Instant expiresAt) {
}