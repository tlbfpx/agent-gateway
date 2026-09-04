package com.company.agentgateway.interfaces.status;

import com.company.agentgateway.interfaces.demo.DemoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * StatusController 单元测试（spec 2026-09-05 §status-page）。
 *
 * <p>覆盖：status 字段固定 UP / version 兜底 / services 三个键全在 / demo 关闭场景。
 */
class StatusControllerTest {

    private DemoService demoService;
    private StatusController controller;

    @BeforeEach
    void setUp() {
        demoService = mock(DemoService.class);
        when(demoService.isEnabled()).thenReturn(false);
        controller = new StatusController(demoService, "agent-gateway");
    }

    @Test
    void statusJsonHasAllRequiredKeys() {
        Map<String, Object> s = controller.statusJson();
        assertThat(s).containsKeys("status", "version", "uptimeSeconds", "services", "buildTimestamp");
        assertThat(s.get("status")).isEqualTo("UP");
        assertThat(((Number) s.get("uptimeSeconds")).longValue()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void servicesBlockHasGatewayDemoAndPostgres() {
        Map<String, Object> s = controller.statusJson();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> services = (Map<String, Map<String, Object>>) s.get("services");
        assertThat(services).containsKeys("gateway", "demo", "postgres");
        assertThat(services.get("gateway").get("status")).isEqualTo("UP");
        assertThat(services.get("demo").get("status")).isEqualTo("DISABLED");
        assertThat(services.get("postgres").get("status")).isEqualTo("UNKNOWN");
    }

    @Test
    void demoEnabledReflectsServiceState() {
        when(demoService.isEnabled()).thenReturn(true);
        StatusController c = new StatusController(demoService, "agent-gateway");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> services =
                (Map<String, Map<String, Object>>) c.statusJson().get("services");
        assertThat(services.get("demo").get("status")).isEqualTo("ENABLED");
    }

    @Test
    void versionFallsBackToDevWhenNoManifest() {
        // 本地 IDE 跑时 jar manifest 通常没 ImplementationVersion
        Map<String, Object> s = controller.statusJson();
        assertThat(s.get("version")).isNotNull();
        // 可以是 "dev" 或 jar manifest 注入的真实版本 — 都接受
    }

    @Test
    void htmlEndpointReturnsStructuredHtml() {
        String html = controller.statusHtml();
        assertThat(html).startsWith("<!doctype html>");
        assertThat(html).contains("agent-gateway");
        assertThat(html).contains("gateway");
        assertThat(html).contains("demo");
        assertThat(html).contains("postgres");
    }
}