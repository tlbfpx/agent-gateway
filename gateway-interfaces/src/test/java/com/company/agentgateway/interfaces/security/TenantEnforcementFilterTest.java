package com.company.agentgateway.interfaces.security;

import com.company.agentgateway.domain.iam.AuthChannel;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.MultiTenantAuthenticator;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import com.company.agentgateway.infra.security.ApiKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TenantEnforcementFilter 单元测试（spec §6.2 多租户隔离 · P0）。
 *
 * <p>核心覆盖：
 * <ul>
 *   <li>不带 X-API-Key → 跳过（公开端点或 webhook callback）</li>
 *   <li>X-API-Key + 合法 X-Tenant-Id → 通过，header 被覆盖</li>
 *   <li>X-API-Key + 越权 X-Tenant-Id → 403 GW-1003</li>
 *   <li>公开路径（/v1/auth/signup 等）→ 跳过</li>
 *   <li>key 无效 → 静默放行（后续 AdminTokenFilter / 业务 RBAC 兜底）</li>
 * </ul>
 */
class TenantEnforcementFilterTest {

    private MultiTenantAuthenticator authenticator;
    private ApiKeyStore apiKeyStore;
    private TenantEnforcementFilter filter;

    @BeforeEach
    void setUp() {
        authenticator = mock(MultiTenantAuthenticator.class);
        apiKeyStore = mock(ApiKeyStore.class);
        filter = new TenantEnforcementFilter(authenticator, apiKeyStore);
    }

    @Test
    void noApiKeySkips() throws Exception {
        MockHttpServletRequest req = req("/v1/admin/api-keys", null, "bogus");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, (jakarta.servlet.ServletRequest r, jakarta.servlet.ServletResponse s) -> {
            ((jakarta.servlet.http.HttpServletResponse) s).setStatus(200);
        });

        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void publicPathSkips() throws Exception {
        MockHttpServletRequest req = req("/v1/auth/signup", "sk-X", null);
        req.setMethod("POST");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, (r, s) ->
                ((jakarta.servlet.http.HttpServletResponse) s).setStatus(200));

        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void validKeyAndTenantPasses() throws Exception {
        when(apiKeyStore.findByKey("sk-valid")).thenReturn(Optional.of(
                new ApiKeyStore.ApiKeyBinding(
                        new TenantId("primary"), new UserId("u"),
                        Set.of(), Set.of(), false, Set.of(new TenantId("primary")), null)));
        when(authenticator.authenticate(eq("sk-valid"), eq("primary")))
                .thenReturn(new AuthPrincipal(new UserId("u"), new TenantId("primary"),
                        java.util.Set.of(), java.util.Set.of(), AuthChannel.API_KEY));

        MockHttpServletRequest req = req("/v1/admin/api-keys", "sk-valid", "primary");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, (r, s) -> {
            jakarta.servlet.http.HttpServletRequest hr = (jakarta.servlet.http.HttpServletRequest) r;
            // 验证 ATTR_VERIFIED_TENANT 写入 + X-Tenant-Id 被覆盖
            assertThat(r.getAttribute(TenantEnforcementFilter.ATTR_VERIFIED_TENANT))
                    .isEqualTo("primary");
            assertThat(hr.getHeader("X-Tenant-Id")).isEqualTo("primary");
            ((jakarta.servlet.http.HttpServletResponse) s).setStatus(200);
        });

        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void mismatchedTenantReturns403() throws Exception {
        when(apiKeyStore.findByKey("sk-primary-only")).thenReturn(Optional.of(
                new ApiKeyStore.ApiKeyBinding(
                        new TenantId("primary"), new UserId("u"),
                        Set.of(), Set.of(), false, Set.of(new TenantId("primary")), null)));
        when(authenticator.authenticate(eq("sk-primary-only"), eq("other-tenant")))
                .thenThrow(new com.company.agentgateway.domain.iam.AuthorizationException(
                        "Key not authorized for tenant: other-tenant"));

        MockHttpServletRequest req = req("/v1/admin/api-keys", "sk-primary-only", "other-tenant");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, (r, s) -> {
            throw new AssertionError("chain must not be invoked on 403");
        });

        assertThat(resp.getStatus()).isEqualTo(403);
        assertThat(resp.getContentAsString()).contains("GW-1003");
    }

    @Test
    void unknownApiKeyIsSilentlyPassed() throws Exception {
        // key 在 store 找不到（已被吊销 / 已过期）→ 不在本过滤器阻断；后续兜底
        when(apiKeyStore.findByKey("sk-gone")).thenReturn(Optional.empty());

        MockHttpServletRequest req = req("/v1/admin/api-keys", "sk-gone", "primary");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, (r, s) ->
                ((jakarta.servlet.http.HttpServletResponse) s).setStatus(200));

        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void authenticationFailureIsSilentlyPassed() throws Exception {
        // key 存在但 authenticator 抛 AuthenticationException（如解析失败）→ 不在本过滤器阻断
        when(apiKeyStore.findByKey("sk-bad")).thenReturn(Optional.of(
                new ApiKeyStore.ApiKeyBinding(
                        new TenantId("primary"), new UserId("u"),
                        Set.of(), Set.of(), false, Set.of(new TenantId("primary")), null)));
        when(authenticator.authenticate(eq("sk-bad"), any()))
                .thenThrow(new com.company.agentgateway.domain.iam.AuthenticationException("bad"));

        MockHttpServletRequest req = req("/v1/admin/api-keys", "sk-bad", "primary");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, (r, s) ->
                ((jakarta.servlet.http.HttpServletResponse) s).setStatus(200));

        assertThat(resp.getStatus()).isEqualTo(200);
    }

    private static MockHttpServletRequest req(String path, String apiKey, String tenant) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", path);
        r.setRequestURI(path);
        if (apiKey != null) r.addHeader("X-API-Key", apiKey);
        if (tenant != null) r.addHeader("X-Tenant-Id", tenant);
        return r;
    }
}