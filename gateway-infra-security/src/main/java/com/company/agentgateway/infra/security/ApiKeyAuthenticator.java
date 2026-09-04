package com.company.agentgateway.infra.security;

import com.company.agentgateway.domain.iam.AuthChannel;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.Authenticator;
import com.company.agentgateway.domain.iam.AuthenticationException;

/**
 * Authenticator 的 API Key 通道实现（spec §6.1，一期）。
 *
 * <p>校验 X-API-Key → 查 {@link ApiKeyStore} → 构造 {@link AuthPrincipal}（channel=API_KEY）。
 * 无效/吊销/缺失抛 {@link AuthenticationException}（调用方转 401）。
 */
public class ApiKeyAuthenticator implements Authenticator {

    private final ApiKeyStore store;

    public ApiKeyAuthenticator(ApiKeyStore store) {
        this.store = store;
    }

    @Override
    public AuthPrincipal authenticate(String apiKey) {
        ApiKeyStore.ApiKeyBinding binding = store.findByKey(apiKey)
                .orElseThrow(() -> new AuthenticationException("Invalid or missing API key"));
        return new AuthPrincipal(
                binding.user(),
                binding.tenant(),
                binding.agentGrants(),
                binding.allowedModels(),
                AuthChannel.API_KEY);
    }
}
