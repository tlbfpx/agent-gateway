package com.company.agentgateway.interfaces.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * OIDC 单点登录配置（spec 2026-09-05 §sso-oidc §3）。
 *
 * <p>由 {@code gateway.oidc.*} 配置驱动；prod 默认关闭，
 * dev/staging 通过 {@环境 { set OIDC_ENABLED=true} + 真实 IdP 参数启用。
 *
 * <ul>
 *   <li>{@code enabled} — 总开关；关闭时 {@code /v1/auth/oidc/**} 全部 404</li>
 *   <li>{@code issuer} — IdP issuer URL（OIDC discovery base）</li>
 *   <li>{@code clientId} / {@code clientSecret} — OAuth2 client credentials</li>
 *   <li>{@code scopes} — 申请的 claims（默认 openid email profile）</li>
 *   <li>{@code defaultRedirectReturnTo} — 登录成功后默认跳转；前端 SPA 通常是 '/'</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "gateway.oidc")
public class OIDCConfig {

    /** 总开关；env {@环境 { set OIDC_ENABLED}。prod 默认 false。 */
    private boolean enabled = false;

    /** IdP issuer URL；如 https://login.microsoftonline.com/{tenant}/v2.0 */
    private String issuer = "";

    /** OAuth2 client_id */
    private String clientId = "";

    /** OAuth2 client_secret（建议从 Secret 注入；prod 必走 env） */
    private String clientSecret = "";

    /** 申请 scopes；默认 ['openid', 'email', 'profile'] */
    private List<String> scopes = List.of("openid", "email", "profile");

    /** 登录成功默认 returnTo */
    private String defaultRedirectReturnTo = "/";

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
}