package com.company.agentgateway.infra.security;

import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.MultiTenantAuthenticator;
import com.company.agentgateway.domain.shared.TenantId;

/**
 * 多租户切换认证（spec §6.2 二期）：
 * 1) 先按 API Key 认证（得主租户 principal）
 * 2) 若带 X-Tenant-Id 且 ≠ 主租户：校验在 key 的授权租户列表内 → switchTenant；
 *    不在列表 → 拒绝（跨租户越权）
 * 不带 X-Tenant-Id = 用主租户（行为与一期完全一致）。
 *
 * <p>实现 domain.iam.MultiTenantAuthenticator 端口——application 层通过该端口访问，
 * 不直接依赖本具体类（保持分层：application → domain ← infra）。
 */
public class TenantAwareAuthenticator implements MultiTenantAuthenticator {

    private final ApiKeyStore store;

    public TenantAwareAuthenticator(ApiKeyStore store) {
        this.store = store;
    }

    @Override
    public AuthPrincipal authenticate(String apiKey) {
        return authenticate(apiKey, null);
    }

    @Override
    public AuthPrincipal authenticate(String apiKey, String tenantIdHeader) {
        AuthPrincipal primary = new ApiKeyAuthenticator(store).authenticate(apiKey);
        if (tenantIdHeader == null || tenantIdHeader.isBlank()) {
            return primary;
        }
        var target = new TenantId(tenantIdHeader.trim());
        var binding = store.findByKey(apiKey)
                .orElseThrow(() -> new com.company.agentgateway.domain.iam.AuthenticationException(
                        "Invalid or missing API key"));
        if (!binding.allowsTenant(target)) {
            throw new com.company.agentgateway.domain.iam.AuthorizationException(
                    "Key not authorized for tenant: " + target.value());
        }
        return primary.switchTenant(target);
    }
}
