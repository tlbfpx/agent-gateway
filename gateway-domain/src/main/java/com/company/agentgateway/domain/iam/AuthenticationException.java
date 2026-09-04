package com.company.agentgateway.domain.iam;

/** 认证失败（无效/吊销/缺失凭证）。对应 401。 */
public class AuthenticationException extends RuntimeException {
    public AuthenticationException(String message) {
        super(message);
    }
}
