package com.company.agentgateway.interfaces.security;

import com.company.agentgateway.domain.iam.MultiTenantAuthenticator;
import com.company.agentgateway.domain.iam.AuthorizationException;
import com.company.agentgateway.infra.security.ApiKeyStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * 租户强制过滤器（spec §6.2 多租户隔离 · P0 安全漏洞修复）。
 *
 * <p>漏洞：之前所有 admin 端点直接读 {@code X-Tenant-Id} 头作为查询条件，
 * 但调用方只要持有一个合法 API Key，就可以伪造 X-Tenant-Id 越权读任何租户的数据。
 * {@link com.company.agentgateway.infra.security.TenantAwareAuthenticator} 已有正确
 * 校验逻辑，但仅在显式调用 {@code authenticate(apiKey, tenantId)} 时生效，
 * 而很多端点只取了头不调 authenticator。
 *
 * <p>本过滤器统一在请求早期执行 authenticator.authenticate(apiKey, X-Tenant-Id)：
 * <ul>
 *   <li>无 X-API-Key → 跳过（公开端点：signup/demo/openapi/admin/auth/login）</li>
 *   <li>API Key 无效 → 401 GW-1401</li>
 *   <li>key 不在该租户授权列表 → 403 GW-1003</li>
 *   <li>成功 → 把 verified tenant 写到 {@code request.setAttribute(ATTR_VERIFIED_TENANT, ..)}
 *       并 {@code X-Tenant-Id} 头覆盖为验证后值（防 X-Tenant-Id 不一致导致数据查询错位）</li>
 * </ul>
 *
 * <p>位置：在 AdminTokenFilter(15) 之后、RbacFilter(20) 之前，
 * 让静态 admin token 鉴权先跑（admin 端点不需要走 tenant 校验）。
 * 但对 {@code /v1/admin/**} 之外的业务端点也要保护（如 {@code /v1/agents/**}），
 * 所以本过滤器不限 URL 前缀，仅对带 X-API-Key 的请求生效。
 *
 * <p>open by design：不强制所有路径都走 authenticator（避免误伤
 * 公开端点）；{@code AdminTokenFilter} 仍负责 /v1/admin/** 的静态 token 兜底。
 */
@Component
@Order(16) // AdminTokenFilter=15, RbacFilter=20; 本过滤器夹在中间
public class TenantEnforcementFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantEnforcementFilter.class);

    /** 验证通过后把 tenant 写到 request attribute，下游若需要可直接取。 */
    public static final String ATTR_VERIFIED_TENANT = "agent-gateway.verifiedTenant";

    private static final Set<String> PUBLIC_PATH_PREFIXES = Set.of(
            "/v1/auth/",          // signup / login
            "/v1/admin/auth/",    // admin login（避免循环：登录时还没有 key）
            "/v1/demo/",          // demo bootstrap
            "/v1/openapi",        // spec
            "/actuator/",         // 健康检查
            "/v1/codegen/",       // 生成 SDK 不需鉴权（前端 "下载 SDK" 按钮）
            "/v1/openapi/bundle"  // 同上
    );

    private final MultiTenantAuthenticator authenticator;
    private final ApiKeyStore apiKeyStore;

    public TenantEnforcementFilter(MultiTenantAuthenticator authenticator, ApiKeyStore apiKeyStore) {
        this.authenticator = authenticator;
        this.apiKeyStore = apiKeyStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI();
        if (isPublic(path)) {
            chain.doFilter(req, resp);
            return;
        }
        String apiKey = req.getHeader("X-API-Key");
        if (apiKey == null || apiKey.isBlank()) {
            // 没带 API Key 的请求不属于"用户租户数据"语义（如 webhook callback），
            // 跳过强制；具体端点的鉴权由各自 Filter 或 RBAC 负责。
            chain.doFilter(req, resp);
            return;
        }

        // 先确认 key 存在（authenticator.authenticate 在 key 错时也抛 AuthenticationException，
        // 这里区分一下让日志更清楚）
        Optional<ApiKeyStore.ApiKeyBinding> bindingOpt = apiKeyStore.findByKey(apiKey);
        if (bindingOpt.isEmpty()) {
            // key 已过期或吊销——authenticator 也会抛 AuthenticationException，这里静默放行让后续鉴权兜底
            chain.doFilter(req, resp);
            return;
        }

        String tenantHeader = req.getHeader("X-Tenant-Id");
        try {
            var principal = authenticator.authenticate(apiKey, tenantHeader);
            String verifiedTenant = principal.tenant().value();
            req.setAttribute(ATTR_VERIFIED_TENANT, verifiedTenant);
            // 覆盖下游读取的 header — 用 HttpServletRequestWrapper
            chain.doFilter(new TenantOverrideRequest(req, verifiedTenant), resp);
        } catch (AuthorizationException ex) {
            log.warn("TenantEnforcementFilter DENIED path={} msg={}", path, ex.getMessage());
            writeError(resp, 403, "GW-1003: " + ex.getMessage());
        } catch (com.company.agentgateway.domain.iam.AuthenticationException ex) {
            // key 不对；不在本过滤器管（admin token 校验后续还会跑），放行让后续兜底
            chain.doFilter(req, resp);
        }
    }

    private static boolean isPublic(String path) {
        if (path == null) return true;
        for (String p : PUBLIC_PATH_PREFIXES) {
            if (path.startsWith(p)) return true;
        }
        return false;
    }

    private static void writeError(HttpServletResponse resp, int status, String body)
            throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"error\":\"" + body.replace("\"", "\\\"") + "\"}");
    }

    /** Wrapper：覆盖 X-Tenant-Id header 为认证通过后的值，防中间件读取伪造。 */
    private static final class TenantOverrideRequest extends jakarta.servlet.http.HttpServletRequestWrapper {
        private final String tenant;

        TenantOverrideRequest(HttpServletRequest request, String tenant) {
            super(request);
            this.tenant = tenant;
        }

        @Override
        public String getHeader(String name) {
            if ("X-Tenant-Id".equalsIgnoreCase(name)) return tenant;
            return super.getHeader(name);
        }
    }
}