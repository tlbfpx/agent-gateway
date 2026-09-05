package com.company.agentgateway.interfaces.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OIDC UserInfo 端点响应（OpenID Connect Core §5.1）。
 *
 * <p>IdP 保证 email 字段（我们申请了 email scope）。
 * 邮箱作为本地 AdminUser 的唯一键。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OidcUserInfo(
        @JsonProperty("sub") String sub,
        @JsonProperty("email") String email,
        @JsonProperty("email_verified") Boolean emailVerified,
        @JsonProperty("name") String name,
        @JsonProperty("preferred_username") String preferredUsername) {
}