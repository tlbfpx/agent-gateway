package com.company.agentgateway.domain.iam;

/**
 * 出站端口：认证（spec §6.1）。由 gateway-infra-security 实现。
 *
 * <p>一期：API Key 通道（authenticate(apiKey) → AuthPrincipal）。
 * 二期：SSO/OIDC（同一端口，按 channel 分发，AuthPrincipal.channel 区分）。
 *
 * <p>无效/吊销 key 抛 {@link AuthenticationException}（调用方转 401）。
 */
public interface Authenticator {

    /** 认证 API Key，返回身份主体。无效/吊销抛 AuthenticationException。 */
    AuthPrincipal authenticate(String apiKey);
}
