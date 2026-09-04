package com.company.agentgateway.interfaces.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 管理端点轻量鉴权（gateway.security.admin-token）。
 *
 * <p>守护 {@code /v1/admin/**}：配置了 {@code gateway.security.admin-token}（非空）时，
 * 请求必须携带匹配的 {@code X-Admin-Token} 头，否则 401（GW-1401）。
 * 该凭据与终端用户 {@code X-API-Key} 相互独立：管理端点只看 X-Admin-Token，
 * 不再依赖业务 API Key。
 *
 * <p>默认 token 为空 = 关闭本鉴权（向后兼容，现有部署行为不变）。
 * 比较使用常数时间（MessageDigest.isEqual），避免时序侧信道。
 */
@Component
@Order(15) // 在 RbacFilter(20) 之前，管理路径先于业务 RBAC 预检
public class AdminTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminTokenFilter.class);

    private static final String HEADER = "X-Admin-Token";

    private final String adminToken;

    public AdminTokenFilter(@Value("${gateway.security.admin-token:}") String adminToken) {
        this.adminToken = adminToken == null ? "" : adminToken.trim();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws jakarta.servlet.ServletException, IOException {
        if (adminToken.isEmpty() || !req.getRequestURI().startsWith("/v1/admin/")) {
            // 未启用或非管理路径：放行（向后兼容）
            chain.doFilter(req, resp);
            return;
        }
        String presented = req.getHeader(HEADER);
        if (presented == null || !constantTimeEquals(adminToken, presented)) {
            log.warn("AdminTokenFilter DENIED path={} reason={}",
                    req.getRequestURI(), presented == null ? "missing-header" : "mismatch");
            resp.setStatus(401);
            resp.setContentType("application/json");
            resp.getWriter().write(
                    "{\"error\":\"GW-1401: admin token missing or invalid\"}");
            return;
        }
        chain.doFilter(req, resp);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
