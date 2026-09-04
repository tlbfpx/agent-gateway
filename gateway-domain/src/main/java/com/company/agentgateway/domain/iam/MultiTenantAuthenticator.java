package com.company.agentgateway.domain.iam;

/**
 * 多租户切换认证端口（spec §6.2 二期）。
 *
 * <p>扩展 {@link Authenticator}，在保留单租户 {@code authenticate(apiKey)} 行为的同时，
 * 支持通过 X-Tenant-Id 头在同一 Key 授权的多租户间切换（须校验 target 在 key 授权列表内）。
 *
 * <p>application 层通过 {@code instanceof MultiTenantAuthenticator} 判断是否支持多租户——
 * 该判断下沉至 domain 包内，合法；未实现此端口的 Authenticator 退化为单租户语义
 * （{@code authenticate(apiKey, null/blank)} 等价 {@code authenticate(apiKey)}）。
 *
 * <p>跨租户越权（target 不在 key 授权列表内）由实现层抛 {@link AuthorizationException}。
 */
public interface MultiTenantAuthenticator extends Authenticator {

    /**
     * 带租户选择的认证。
     *
     * @param apiKey           调用方 API Key
     * @param tenantIdHeader   来自 X-Tenant-Id 头的租户值；null/空白 = 主租户
     * @return                认证后的身份主体（tenant 已切换或保持主租户）
     * @throws AuthenticationException 无效/吊销的 Key
     * @throws AuthorizationException  target 租户不在该 Key 授权列表内
     */
    AuthPrincipal authenticate(String apiKey, String tenantIdHeader);
}