package com.company.agentgateway.interfaces.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OIDC token endpoint 响应（RFC 6749 §5.1）。
 *
 * <p>{@code POST /token} 返回字段；access_token / id_token / refresh_token 任一可空。
 * 本轮不用 access_token / refresh_token（下轮加 refresh）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OidcTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("id_token") String idToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") Integer expiresIn,
        @JsonProperty("error") String error,
        @JsonProperty("error_description") String errorDescription) {
}