package com.company.agentgateway.domain.iam;

/** 授权失败（无权调用 Agent/使用模型）。对应 403。 */
public class AuthorizationException extends RuntimeException {
    public AuthorizationException(String message) {
        super(message);
    }
}
