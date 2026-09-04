package com.company.agentgateway.interfaces.security;

import com.company.agentgateway.domain.iam.AgentGrant;
import com.company.agentgateway.domain.iam.AuthChannel;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.Authenticator;
import com.company.agentgateway.domain.iam.AuthorizationException;
import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.iam.RbacCheckPoint;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RbacFilterTest {

    private final Authenticator auth = mock(Authenticator.class);
    private final AuthorizationService rbac = mock(AuthorizationService.class);

    private final AuthPrincipal principal = new AuthPrincipal(
            new UserId("u1"), new TenantId("t1"),
            Set.of(new AgentGrant("hr-agent", Set.of())), Set.of(), AuthChannel.API_KEY);

    private HttpServletRequest req(String uri, String apiKey) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(uri);
        when(req.getHeader("X-API-Key")).thenReturn(apiKey);
        return req;
    }

    @Test
    void deniedPath_returns403_andDoesNotCallFilterChain() throws Exception {
        when(auth.authenticate("sk-test")).thenReturn(principal);
        doThrow(new AuthorizationException("GW-1003: denied"))
                .when(rbac).checkInvokeAgent(any(), any(), any());

        RbacFilter filter = new RbacFilter(auth, rbac);
        HttpServletRequest req = req("/v1/chat/hr-agent", "sk-test");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getWriter()).thenReturn(new java.io.PrintWriter(java.io.Writer.nullWriter()));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(rbac).checkInvokeAgent(eq(principal), eq("hr-agent"), eq(RbacCheckPoint.RBAC_FILTER));
        verify(resp).setStatus(403);
        verify(chain, never()).doFilter(req, resp);
    }

    @Test
    void allowedPath_callsFilterChain() throws Exception {
        when(auth.authenticate("sk-test")).thenReturn(principal);

        RbacFilter filter = new RbacFilter(auth, rbac);
        HttpServletRequest req = req("/v1/chat/hr-agent", "sk-test");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(rbac).checkInvokeAgent(principal, "hr-agent", RbacCheckPoint.RBAC_FILTER);
        verify(chain, times(1)).doFilter(req, resp);
    }

    @Test
    void nonRbacPath_passesThroughWithoutCheck() throws Exception {
        RbacFilter filter = new RbacFilter(auth, rbac);
        HttpServletRequest req = req("/v1/admin/api-keys", "sk-test");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(auth, never()).authenticate(any());
        verify(rbac, never()).checkInvokeAgent(any(), any(), any());
        verify(chain, times(1)).doFilter(req, resp);
    }

    @Test
    void noApiKey_passesThrough_authenticationLeftToExistingLayer() throws Exception {
        RbacFilter filter = new RbacFilter(auth, rbac);
        HttpServletRequest req = req("/v1/chat/hr-agent", null);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(auth, never()).authenticate(any());
        verify(rbac, never()).checkInvokeAgent(any(), any(), any());
        verify(chain, times(1)).doFilter(req, resp);
    }

    @Test
    void agentSubPath_alsoExtractsAgentName() throws Exception {
        when(auth.authenticate("sk-test")).thenReturn(principal);
        RbacFilter filter = new RbacFilter(auth, rbac);
        HttpServletRequest req = req("/v1/agents/finance-agent/invoke", "sk-test");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(rbac).checkInvokeAgent(principal, "finance-agent", RbacCheckPoint.RBAC_FILTER);
        verify(chain, times(1)).doFilter(req, resp);
    }
}
