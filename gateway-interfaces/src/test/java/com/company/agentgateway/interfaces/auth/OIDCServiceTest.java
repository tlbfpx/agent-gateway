package com.company.agentgateway.interfaces.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OIDCService 单元测试（spec 2026-09-05 §sso-oidc §7）。
 *
 * <p>覆盖：disabled 抛错 / issuer 缺失抛错 / 构造 URL 含必要参数 /
 * state 包 returnTo / extractReturnTo 能反解。
 */
class OIDCServiceTest {

    private OIDCConfig config;
    private OIDCService service;

    @BeforeEach
    void setUp() {
        config = new OIDCConfig();
        config.setEnabled(true);
        config.setIssuer("https://login.example.com");
        config.setClientId("test-client");
        config.setClientSecret("test-secret");
        config.setScopes(List.of("openid", "email"));
        config.setDefaultRedirectReturnTo("/");
        service = new OIDCService(config);
    }

    @Test
    void disabledThrows() {
        config.setEnabled(false);
        assertThatThrownBy(() -> service.buildAuthorizationRequest("/dashboard"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void missingIssuerThrows() {
        config.setIssuer("");
        assertThatThrownBy(() -> service.buildAuthorizationRequest(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer");
    }

    @Test
    void missingClientIdThrows() {
        config.setClientId("");
        assertThatThrownBy(() -> service.buildAuthorizationRequest(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client");
    }

    @Test
    void authUrlContainsRequiredParameters() {
        OIDCService.AuthRequest req = service.buildAuthorizationRequest("/dashboard");
        assertThat(req.authorizationUrl()).startsWith("https://login.example.com/authorize?");
        assertThat(req.authorizationUrl()).contains("response_type=code");
        assertThat(req.authorizationUrl()).contains("client_id=test-client");
        assertThat(req.authorizationUrl()).contains("scope=openid+email");
        assertThat(req.authorizationUrl()).contains("state=");
        assertThat(req.authorizationUrl()).contains("nonce=");
        assertThat(req.state()).isNotBlank();
        assertThat(req.nonce()).isNotBlank();
    }

    @Test
    void stateEncodesReturnTo() {
        OIDCService.AuthRequest req = service.buildAuthorizationRequest("/admin-users");
        // state 格式: "<random>.<base64url(/admin-users)>"
        int idx = req.state().indexOf('.');
        assertThat(idx).isGreaterThan(0);
        String returnTo = OIDCService.extractReturnTo(req.state());
        assertThat(returnTo).isEqualTo("/admin-users");
    }

    @Test
    void defaultReturnToUsedWhenNull() {
        OIDCService.AuthRequest req = service.buildAuthorizationRequest(null);
        String returnTo = OIDCService.extractReturnTo(req.state());
        assertThat(returnTo).isEqualTo("/");
    }

    @Test
    void extractReturnToReturnsNullOnMalformed() {
        assertThat(OIDCService.extractReturnTo(null)).isNull();
        assertThat(OIDCService.extractReturnTo("no-dot")).isNull();
        assertThat(OIDCService.extractReturnTo("prefix.")).isNull(); // 后段空
    }

    @Test
    void eachRequestProducesDifferentStateAndNonce() {
        var a = service.buildAuthorizationRequest("/x");
        var b = service.buildAuthorizationRequest("/x");
        assertThat(a.state()).isNotEqualTo(b.state());
        assertThat(a.nonce()).isNotEqualTo(b.nonce());
    }
}