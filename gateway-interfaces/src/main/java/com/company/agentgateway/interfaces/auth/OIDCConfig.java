package com.company.agentgateway.interfaces.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OIDC 单点登录配置（spec 2026-09-05 §sso-oidc §3）。
 *
 * <p>由 {@code gateway.oidc.*} 配置驱动；prod 默认关闭。
 *
 * <h3>单租户模式（spec §3 round 1-4）</h3>
 * <ul>
 *   <li>{@code enabled} — 总开关</li>
 *   <li>{@code issuer} — IdP issuer URL（OIDC discovery base）</li>
 *   <li>{@code clientId} / {@code clientSecret} — OAuth2 client credentials</li>
 *   <li>{@code scopes} — 申请的 claims（默认 openid email profile）</li>
 *   <li>{@code defaultRedirectReturnTo} — 登录成功后默认跳转</li>
 * </ul>
 *
 * <h3>多租户 SaaS 模式（spec §3 future / round 5+）</h3>
 * <ul>
 *   <li>{@code tenants.<tenantId>.{issuer,clientId,clientSecret,scopes}} —
 *       每租户独立 IdP（如 Azure AD 多租户 / Okta 多 org / Auth0 多 domain）</li>
 *   <li>未配 tenants 或 tenant 不在 map 中 → 走全局 fallback</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "gateway.oidc")
public class OIDCConfig {

    /** 总开关；prod 默认 false。 */
    private boolean enabled = false;

    /** IdP issuer URL；如 https://login.microsoftonline.com/{tenant}/v2.0 */
    private String issuer = "";

    /** OAuth2 client_id */
    private String clientId = "";

    /** OAuth2 client_secret（建议从 Secret 注入） */
    private String clientSecret = "";

    /** 申请 scopes；默认 ['openid', 'email', 'profile'] */
    private List<String> scopes = List.of("openid", "email", "profile");

    /** 登录成功默认 returnTo */
    private String defaultRedirectReturnTo = "/";

    /** 多租户 SaaS：tenantId → tenant-specific OIDC config；未配 → 走全局 fallback */
    private Map<String, TenantOverride> tenants = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

    public List<String> getScopes() { return scopes; }
    public void setScopes(List<String> scopes) { this.scopes = scopes; }

    public String getDefaultRedirectReturnTo() { return defaultRedirectReturnTo; }
    public void setDefaultRedirectReturnTo(String defaultRedirectReturnTo) {
        this.defaultRedirectReturnTo = defaultRedirectReturnTo;
    }

    public Map<String, TenantOverride> getTenants() { return tenants; }
    public void setTenants(Map<String, TenantOverride> tenants) { this.tenants = tenants; }

    /** 配置自检端点：OIDC 启用时返回 issuer，否则 null。 */
    public String getId() { return enabled ? issuer : null; }

    /**
     * 给定 tenantId 返回其专属 OIDC config；未配 → 返回 null（让 caller 走全局）。
     */
    public TenantOverride tenantOverride(String tenantId) {
        if (tenantId == null) return null;
        return tenants.get(tenantId);
    }

    /** 多租户 SaaS 用的 tenant-specific OIDC config（spec §3 future）。 */
    public static class TenantOverride {
        private String issuer;
        private String clientId;
        private String clientSecret;
        private List<String> scopes;

        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public List<String> getScopes() { return scopes; }
        public void setScopes(List<String> scopes) { this.scopes = scopes; }
    }
}