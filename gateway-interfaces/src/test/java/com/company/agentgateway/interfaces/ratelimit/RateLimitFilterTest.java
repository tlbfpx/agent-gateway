package com.company.agentgateway.interfaces.ratelimit;

import com.company.agentgateway.domain.ratelimit.RateLimitDecision;
import com.company.agentgateway.domain.ratelimit.RateLimitPolicy;
import com.company.agentgateway.domain.ratelimit.RateLimiter;
import com.company.agentgateway.infra.persistence.ratelimit.TokenBucketRateLimiter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * RateLimitFilter Web 层测试(mock FilterChain 验证 doFilter 调用 / 短路)。
 */
class RateLimitFilterTest {

    private RateLimiter limiter;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        limiter = new TokenBucketRateLimiter();
        // 容量 3,容易触发 block
        filter = new RateLimitFilter(limiter, 3, 1);
    }

    @Test
    void allowsRequestAndSetsHeaders() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/chat/completions");
        req.addHeader("X-API-Key", "sk-test-1234");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus());
        assertEquals("3", resp.getHeader("X-RateLimit-Limit"));
        assertNotNull(resp.getHeader("X-RateLimit-Remaining"));
        assertNotNull(resp.getHeader("X-RateLimit-Reset"));
        verify(chain).doFilter(req, resp);
    }

    @Test
    void blocksAfterCapacity() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/mcp/test");
            req.addHeader("X-API-Key", "sk-test-1234");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(req, resp, chain);
            assertEquals(200, resp.getStatus(), "call " + i);
        }
        // 第 4 次应被限流
        MockHttpServletRequest req4 = new MockHttpServletRequest("POST", "/v1/mcp/test");
        req4.addHeader("X-API-Key", "sk-test-1234");
        MockHttpServletResponse resp4 = new MockHttpServletResponse();
        filter.doFilter(req4, resp4, chain);
        assertEquals(429, resp4.getStatus());
        assertNotNull(resp4.getHeader("Retry-After"));
        assertTrue(resp4.getContentAsString().contains("rate_limited"));
    }

    @Test
    void skipsActuatorAndStatic() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        // 不设置 rate limit headers
        assertEquals(null, resp.getHeader("X-RateLimit-Limit"));
        verify(chain).doFilter(req, resp);
    }

    @Test
    void skipsIndexPage() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, resp, chain);
        assertEquals(null, resp.getHeader("X-RateLimit-Limit"));
    }

    @Test
    void differentApiKeysHaveIndependentBuckets() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        // 耗尽 tenant key1
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest r = new MockHttpServletRequest("POST", "/v1/chat");
            r.addHeader("X-API-Key", "sk-aaaa-xxxx");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(r, res, chain);
        }
        // tenant key2 仍 OK
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/v1/chat");
        r.addHeader("X-API-Key", "sk-bbbb-xxxx");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(r, res, chain);
        assertEquals(200, res.getStatus());
    }

    @Test
    void adminTokenPath_usesAdminTenant() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/admin/plugins/test");
        req.addHeader("X-Admin-Token", "sk-fixed-admin-001");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, resp, chain);
        assertEquals(200, resp.getStatus());
        // 验证 bucket key 用了 admin 命名空间
        RateLimiter.Snapshot s = limiter.snapshot("admin:" + Integer.toHexString(
                "sk-fixed-admin-001".hashCode() & 0xffff));
        assertNotNull(s);
    }

    @Test
    void anonymousFallback_works() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/chat");
        // 无 X-API-Key / X-Admin-Token
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, resp, chain);
        assertEquals(200, resp.getStatus());
    }
}