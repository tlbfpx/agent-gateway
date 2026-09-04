package com.company.agentgateway.domain.orchestration;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.shared.SessionId;

/** 单次调用的上下文（谁、哪个会话、traceId——用于审计/限流/计费关联）。 */
public record InvocationCtx(SessionId session, AuthPrincipal principal, String traceId) {

    /** Workflow / 内部调度占位:无 session/principal(spec C1 §5)。 */
    public static final InvocationCtx NOOP = new InvocationCtx(null, null, null);
}
