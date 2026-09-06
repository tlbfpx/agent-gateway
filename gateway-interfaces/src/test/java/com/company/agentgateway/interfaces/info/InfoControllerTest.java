package com.company.agentgateway.interfaces.info;

import com.company.agentgateway.interfaces.auth.OIDCService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * InfoController 单元测试（spec §info-endpoint）。
 */
class InfoControllerTest {

    private OIDCService oidcService;
    private InfoController controller;

    @BeforeEach
    void setUp() {
        oidcService = mock(OIDCService.class);
        when(oidcService.isEnabled()).thenReturn(false);
        when(oidcService.tenantOverrideCount()).thenReturn(0);
        controller = new InfoController(oidcService);
    }

    @Test
    void returnsAllRequiredKeys() {
        Map<String, Object> info = controller.info();
        assertThat(info).containsKeys(
                "name", "version", "buildTimestamp", "uptimeSeconds",
                "javaVersion", "features");
        assertThat(info.get("name")).isEqualTo("agent-gateway");
    }

    @Test
    void featuresReflectOIDCConfig() {
        when(oidcService.isEnabled()).thenReturn(false);
        when(oidcService.tenantOverrideCount()).thenReturn(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> features = (Map<String, Object>) controller.info().get("features");
        assertThat(features.get("demo")).isEqualTo(true);
        assertThat(features.get("signup")).isEqualTo(true);
        assertThat(features.get("oidc")).isEqualTo(false);
        assertThat(features.get("multiTenantOidc")).isEqualTo(false);
    }

    @Test
    void multiTenantOidcTrueWhenTenantsConfigured() {
        when(oidcService.isEnabled()).thenReturn(true);
        when(oidcService.tenantOverrideCount()).thenReturn(3);
        @SuppressWarnings("unchecked")
        Map<String, Object> features = (Map<String, Object>) controller.info().get("features");
        assertThat(features.get("oidc")).isEqualTo(true);
        assertThat(features.get("multiTenantOidc")).isEqualTo(true);
    }

    @Test
    void uptimeIsNonNegative() {
        long up = (long) controller.info().get("uptimeSeconds");
        assertThat(up).isGreaterThanOrEqualTo(0);
    }
}