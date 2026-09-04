package com.company.agentgateway.infra.a2a;

import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.iam.RbacCheckPoint;
import com.company.agentgateway.domain.registry.AgentCard;
import com.company.agentgateway.domain.orchestration.InvocationCtx;
import com.company.agentgateway.domain.orchestration.ToolEvent;
import com.company.agentgateway.domain.orchestration.ToolPort;

import java.util.concurrent.Flow;

/**
 * ToolPort 实现(spec B §4):委托给 ResilientA2aClient(重试/熔断/多实例)。
 * ResilientA2aClient 内部已含 endpointUrl 校验;此处仅做 Flow 适配。
 *
 * <p><b>D1 二次校验（spec §GW-RBAC-006）</b>：A2A 调用路径最前部插入
 * {@link AuthorizationService#checkInvokeAgent}（checkPoint=A2A）。
 * 失败抛 AuthorizationException（GW-1003），不发起 HTTP。
 * authorizationService 为 null（未装配）或 principal 为 null（内部调用）时跳过校验，
 * 保持既有行为零破坏。
 */
public class A2aToolPort implements ToolPort {

    private final ResilientA2aClient resilient;
    private final AuthorizationService authorizationService; // nullable：未装配时跳过

    public A2aToolPort(ResilientA2aClient resilient) {
        this(resilient, null);
    }

    public A2aToolPort(ResilientA2aClient resilient, AuthorizationService authorizationService) {
        this.resilient = resilient;
        this.authorizationService = authorizationService;
    }

    @Override
    public Flow.Publisher<ToolEvent> invoke(AgentCard agent, String argsJson, InvocationCtx ctx) {
        // 🆕 D1 spec §GW-RBAC-006：A2A 调用前二次校验（纵深防御第②点）
        if (authorizationService != null && ctx != null && ctx.principal() != null) {
            authorizationService.checkInvokeAgent(ctx.principal(), agent.name(), RbacCheckPoint.A2A);
        }
        reactor.core.publisher.Flux<ToolEvent> events =
                resilient.invokeStream(agent, argsJson);
        return A2aFlowAdapter.toFlow(events);
    }
}
