package com.company.agentgateway.domain.routing;

/**
 * 路由策略耗尽(Round 10):候选模型全部超 budget 且 fallbackChain 也失败。
 *
 * <p>调用方应将此异常转换为 NO_FALLBACK 错误返回给用户。
 */
public class RoutingPolicyExhaustedException extends RuntimeException {

    private final String policyId;

    public RoutingPolicyExhaustedException(String policyId, String message) {
        super(message);
        this.policyId = policyId;
    }

    public String policyId() {
        return policyId;
    }
}