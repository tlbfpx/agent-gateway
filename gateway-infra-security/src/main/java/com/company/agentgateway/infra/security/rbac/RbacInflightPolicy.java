package com.company.agentgateway.infra.security.rbac;

import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.AuthorizationException;
import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.iam.RbacCheckPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A2A 调用前二次校验 hook（spec §GW-RBAC-006 + design §3.2）。
 *
 * <p>由 gateway-infra-a2a 在 A2aToolPort.invoke() 路径最前部调用：
 * <pre>{@code
 * rbacInflightPolicy.enforce(principal, agentName);
 * // → AuthorizationService.checkInvokeAgent(..., RbacCheckPoint.A2A)
 * // → 失败抛 AuthorizationException（GW-1003），不发起 HTTP
 * }</pre>
 *
 * <p>checkPoint=A2A 显式传入（spec §GW-RBAC-010，plan 评审 #2/#3 修复），
 * OTel Counter 维度由 Chunk 3 的 RbacMetrics 在决策路径内打点。
 */
@Component
public class RbacInflightPolicy {

    private static final Logger log = LoggerFactory.getLogger(RbacInflightPolicy.class);

    private final AuthorizationService authorizationService;

    public RbacInflightPolicy(AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    /**
     * 调用前强制校验。失败抛 AuthorizationException（GW-1003），调用方需 catch 并短路后续 HTTP。
     *
     * @param principal 调用方身份
     * @param agentName 远端 Agent 名称
     * @throws AuthorizationException 无权调用
     */
    public void enforce(AuthPrincipal principal, String agentName) {
        try {
            authorizationService.checkInvokeAgent(principal, agentName, RbacCheckPoint.A2A);
            log.debug("RbacInflightPolicy ALLOWED user={} agent={}",
                    principal == null ? "null" : principal.user().value(), agentName);
        } catch (AuthorizationException ex) {
            log.warn("RbacInflightPolicy DENIED user={} agent={} msg={}",
                    principal == null ? "null" : principal.user().value(), agentName, ex.getMessage());
            throw ex;
        }
    }
}
