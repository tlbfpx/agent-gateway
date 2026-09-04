package com.company.agentgateway.interfaces.ratelimit;

import com.company.agentgateway.domain.ratelimit.RateLimitDecision;
import com.company.agentgateway.domain.ratelimit.RateLimitPolicy;
import com.company.agentgateway.domain.ratelimit.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 多租户 API 限流 Filter（spec 2026-09-03 §rate-limit §6）。
 *
 * <p>按路径前缀应用：{@code /v1/chat /v1/mcp /v1/admin/*}。
 * 跳过 actuator 与 static 资源。
 *
 * <p>响应头：{@code X-RateLimit-Limit / -Remaining / -Reset} + 429 时
 * {@code Retry-After}(秒)。
 *
 * <p>租户识别优先：
 * <ol>
 *   <li>X-Admin-Token 静态路径(取前缀 hash)</li>
 *   <li>X-API-Key 前缀 hash(anonymous: 前缀)</li>
 *   <li>fallback: "anonymous"</li>
 * </ol>
 */
@Component
@Order(20)  // 在 AdminAuthFilter 之后
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final int defaultCapacity;
    private final double defaultRefillPerSec;

    public RateLimitFilter(
            RateLimiter limiter,
            @Value("${gateway.ratelimit.capacity:200}") int defaultCapacity,
            @Value("${gateway.ratelimit.refill-per-sec:100}") double defaultRefillPerSec) {
        this.limiter = limiter;
        this.defaultCapacity = defaultCapacity;
        this.defaultRefillPerSec = defaultRefillPerSec;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // 限流只针对业务路径;actuator + static 跳过
        return !path.startsWith("/v1/chat")
                && !path.startsWith("/v1/mcp")
                && !path.startsWith("/v1/admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String tenantId = resolveTenantId(req);
        RateLimitPolicy policy = new RateLimitPolicy("tenant:" + tenantId,
                defaultCapacity, defaultRefillPerSec);
        RateLimitDecision d = limiter.tryAcquire(policy);

        // 标准 headers(RFC 6585 / IETF RateLimit Headers)
        resp.setHeader("X-RateLimit-Limit", String.valueOf(d.limit()));
        resp.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, d.remaining())));
        resp.setHeader("X-RateLimit-Reset", String.valueOf(d.resetAtEpochSec()));

        if (!d.allowed()) {
            long retrySec = Math.max(1, d.retryAfterMs() / 1000);
            resp.setHeader("Retry-After", String.valueOf(retrySec));
            resp.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            resp.setContentType("application/json");
            resp.getWriter().write(
                    "{\"error\":\"rate_limited\",\"retry_after_ms\":" + d.retryAfterMs() +
                    ",\"limit\":" + d.limit() + "}");
            return;
        }
        chain.doFilter(req, resp);
    }

    private static String resolveTenantId(HttpServletRequest req) {
        String adminToken = req.getHeader("X-Admin-Token");
        if (adminToken != null && !adminToken.isBlank()) {
            return "admin:" + Integer.toHexString(adminToken.hashCode() & 0xffff);
        }
        String apiKey = req.getHeader("X-API-Key");
        if (apiKey != null && apiKey.length() > 8) {
            return "key:" + apiKey.substring(0, 4);
        }
        return "anonymous";
    }
}