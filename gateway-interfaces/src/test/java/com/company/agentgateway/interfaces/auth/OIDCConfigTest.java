package com.company.agentgateway.interfaces.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OIDCConfig 多租户 SaaS 测试（spec §sso-oidc §3 future round 5+）。
 *
 * <p>单租户模式（全局 config）→ 多租户模式（per-tenant override）→ 自动 fallback
 * 行为都需保证向后兼容。
 */
class OIDCConfigTest {

    private OIDCConfig config;

    @BeforeEach
    void setUp() {
        config = new OIDCConfig();
        config.setEnabled(true);
        config.setIssuer("https://global.example.com");
        config.setClientId("global-client");
        config.setClientSecret("global-secret");
        config.setScopes(List.of("openid", "email", "profile"));
    }

    @Test
    void singleTenantConfig() {
        // 默认行为：enabled + 单 issuer
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getId()).isEqualTo("https://global.example.com");
        assertThat(config.getIssuer()).isEqualTo("https://global.example.com");
        assertThat(config.getClientId()).isEqualTo("global-client");
    }

    @Test
    void disabledReturnsNullId() {
        config.setEnabled(false);
        assertThat(config.getId()).isNull();
    }

    @Test
    void singleTenantTenantOverrideReturnsNull() {
        assertThat(config.tenantOverride("acme")).isNull();
    }

    @Test
    void multiTenantOverrideResolves() {
        OIDCConfig.TenantOverride acme = new OIDCConfig.TenantOverride();
        acme.setIssuer("https://acme.okta.com");
        acme.setClientId("acme-client");
        acme.setClientSecret("acme-secret");
        acme.setScopes(List.of("openid", "email"));

        OIDCConfig.TenantOverride big = new OIDCConfig.TenantOverride();
        big.setIssuer("https://bigcorp.login.microsoftonline.com/abc/v2.0");
        big.setClientId("bigcorp-app");
        big.setClientSecret("bigcorp-secret");
        big.setScopes(List.of("openid", "profile", "groups"));

        config.setTenants(Map.of("acme", acme, "bigcorp", big));

        // 多租户 override 命中
        assertThat(config.tenantOverride("acme").getIssuer()).isEqualTo("https://acme.okta.com");
        assertThat(config.tenantOverride("bigcorp").getClientId()).isEqualTo("bigcorp-app");

        // 未配置的 tenant 返回 null（让 caller 走全局 fallback）
        assertThat(config.tenantOverride("other-corp")).isNull();

        // 全局配置不受 tenants 影响
        assertThat(config.getIssuer()).isEqualTo("https://global.example.com");
    }

    @Test
    void tenantOverrideNullSafe() {
        // tenantId == null / 空字符串 / 未配置 → 全部走 null（即 fallback 路径）
        assertThat(config.tenantOverride(null)).isNull();
        assertThat(config.tenantOverride("")).isNull();
    }
}