package com.company.agentgateway.infra.a2a;

import com.company.agentgateway.domain.iam.AgentGrant;
import com.company.agentgateway.domain.iam.AuthChannel;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.AuthorizationException;
import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.iam.RbacCheckPoint;
import com.company.agentgateway.domain.orchestration.InvocationCtx;
import com.company.agentgateway.domain.orchestration.ToolEvent;
import com.company.agentgateway.domain.registry.AgentCard;
import com.company.agentgateway.domain.shared.SessionId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Flow;


import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * spec §GW-RBAC-006 集成用例：A2A 调用前二次校验 hook（合法路径 + DENIED 路径）。
 */
class A2aToolPortRbacHookTest {

    private final AuthorizationService rbac = mock(AuthorizationService.class);
    private final ResilientA2aClient resilient = mock(ResilientA2aClient.class);

    private final AgentCard agent = new AgentCard("hr-agent", "HR",
            List.of(), null, null, "1.0", true, "http://localhost:9999", List.of());
    private final AuthPrincipal principal = new AuthPrincipal(
            new UserId("u1"), new TenantId("t1"),
            Set.of(new AgentGrant("hr-agent", Set.of())), Set.of(), AuthChannel.API_KEY);
    private final InvocationCtx ctx = new InvocationCtx(new SessionId("s1"), principal, "trace-1");

    @Test
    void a2a_invoke_legalPath_rbacCheckPasses_thenDelegates() {
        when(rbac.canInvokeAgent(any(), any(), any())).thenReturn(true);
        when(resilient.invokeStream(agent, "{}")).thenReturn(Flux.<ToolEvent>empty());

        A2aToolPort port = new A2aToolPort(resilient, rbac);
        Flow.Publisher<ToolEvent> result = port.invoke(agent, "{}", ctx);

        verify(rbac).checkInvokeAgent(eq(principal), eq("hr-agent"), eq(RbacCheckPoint.A2A));
        verify(resilient).invokeStream(agent, "{}");
    }

    @Test
    void a2a_invoke_deniedPath_throwsAuthorizationException_andSkipsHttp() {
        Mockito.doThrow(new AuthorizationException(
                "GW-1003: Principal u1 is not authorized to invoke agent: hr-agent"))
                .when(rbac).checkInvokeAgent(principal, "hr-agent", RbacCheckPoint.A2A);

        A2aToolPort port = new A2aToolPort(resilient, rbac);

        assertThatThrownBy(() -> port.invoke(agent, "{}", ctx))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("GW-1003");
        // 纵深防御：DENIED 时零 HTTP（resilient 不被调用）
        verify(resilient, never()).invokeStream(any(), any());
    }

    @Test
    void a2a_invoke_noAuthService_orNoPrincipal_skipsCheck_zeroBreakage() {
        // 未装配 AuthorizationService：跳过校验，保持既有行为
        A2aToolPort legacy = new A2aToolPort(resilient);
        when(resilient.invokeStream(agent, "{}")).thenReturn(Flux.<ToolEvent>empty());
        legacy.invoke(agent, "{}", ctx);
        verify(rbac, never()).checkInvokeAgent(any(), any(), any());
    }
}
