package com.company.agentgateway.interfaces.security;

import com.company.agentgateway.domain.iam.AuthChannel;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.Authenticator;
import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.iam.RbacCheckPoint;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * spec §GW-RBAC-010：check_point 维度分流验证（interfaces 侧）。
 *
 * <ul>
 *   <li>rbac_filter：RbacFilter 入口（本测试）</li>
 *   <li>a2a：RbacInflightPolicy（gateway-infra-security RbacInflightPolicyTest 已覆盖）</li>
 *   <li>preview：不上 OTel/审计（gateway-infra-security RbacMetricsTest.previewCheckPoint_isNotRecorded 已覆盖）</li>
 * </ul>
 */
class RbacCheckPointRoutingTest {

    @Test
    void httpEntry_routesCheckPointRbacFilter() throws Exception {
        Authenticator auth = mock(Authenticator.class);
        AuthorizationService rbac = mock(AuthorizationService.class);
        AuthPrincipal p = new AuthPrincipal(new UserId("u1"), new TenantId("t1"),
                Set.of(), Set.of(), AuthChannel.API_KEY);
        when(auth.authenticate("sk")).thenReturn(p);

        RbacFilter filter = new RbacFilter(auth, rbac);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/v1/chat/hr-agent");
        when(req.getHeader("X-API-Key")).thenReturn("sk");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getWriter()).thenReturn(new java.io.PrintWriter(java.io.Writer.nullWriter()));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        ArgumentCaptor<RbacCheckPoint> cp = ArgumentCaptor.forClass(RbacCheckPoint.class);
        verify(rbac).checkInvokeAgent(any(), any(), cp.capture());
        assertThat(cp.getValue()).isEqualTo(RbacCheckPoint.RBAC_FILTER);
    }
}
