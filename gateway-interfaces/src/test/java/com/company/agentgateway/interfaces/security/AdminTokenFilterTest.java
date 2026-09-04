package com.company.agentgateway.interfaces.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class AdminTokenFilterTest {

    private HttpServletRequest req(String uri, String adminToken) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(uri);
        when(req.getHeader("X-Admin-Token")).thenReturn(adminToken);
        return req;
    }

    private HttpServletResponse resp() throws Exception {
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getWriter()).thenReturn(new java.io.PrintWriter(java.io.Writer.nullWriter()));
        return resp;
    }

    @Test
    void tokenConfigured_correctHeader_passesThrough() throws Exception {
        AdminTokenFilter filter = new AdminTokenFilter("secret-admin");
        HttpServletRequest req = req("/v1/admin/api-keys", "secret-admin");
        HttpServletResponse resp = resp();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(req, resp);
        verify(resp, never()).setStatus(anyInt());
    }

    @Test
    void tokenConfigured_wrongHeader_returns401_andBlocksChain() throws Exception {
        AdminTokenFilter filter = new AdminTokenFilter("secret-admin");
        HttpServletRequest req = req("/v1/admin/api-keys", "wrong");
        HttpServletResponse resp = resp();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(resp).setStatus(401);
        verify(chain, never()).doFilter(req, resp);
    }

    @Test
    void tokenConfigured_missingHeader_returns401() throws Exception {
        AdminTokenFilter filter = new AdminTokenFilter("secret-admin");
        HttpServletRequest req = req("/v1/admin/models", null);
        HttpServletResponse resp = resp();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(resp).setStatus(401);
        verify(chain, never()).doFilter(req, resp);
    }

    @Test
    void tokenEmpty_allRequestsPassThrough_disabledByDefault() throws Exception {
        AdminTokenFilter filter = new AdminTokenFilter("");
        HttpServletRequest req = req("/v1/admin/api-keys", null);
        HttpServletResponse resp = resp();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(req, resp);
        verify(resp, never()).setStatus(anyInt());
    }

    @Test
    void tokenConfigured_nonAdminPath_passesThrough() throws Exception {
        AdminTokenFilter filter = new AdminTokenFilter("secret-admin");
        HttpServletRequest req = req("/v1/chat/hr-agent", null);
        HttpServletResponse resp = resp();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(req, resp);
        verify(resp, never()).setStatus(anyInt());
    }

    @Test
    void blankConfigToken_treatedAsDisabled() throws Exception {
        AdminTokenFilter filter = new AdminTokenFilter("   ");
        HttpServletRequest req = req("/v1/admin/api-keys", "whatever");
        HttpServletResponse resp = resp();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(req, resp);
    }
}
