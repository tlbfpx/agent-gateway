package com.company.agentgateway.domain.orchestration;
import com.company.agentgateway.domain.registry.AgentCard;
import java.util.concurrent.Flow;

/** 出站端口：调用远程 Agent（A2A）。由 gateway-infra-a2a 实现。argsJson 为 JSON 文本（零框架）。 */
public interface ToolPort {
    Flow.Publisher<ToolEvent> invoke(AgentCard agent, String argsJson, InvocationCtx ctx);
}
