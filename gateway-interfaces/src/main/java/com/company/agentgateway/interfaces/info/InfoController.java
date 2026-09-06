package com.company.agentgateway.interfaces.info;

import com.company.agentgateway.interfaces.auth.OIDCService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * /v1/info — 公开 build/feature 信息（spec 2026-09-05 §info-endpoint）。
 *
 * <p>给集成方 / 监控探针 / 自动化脚本一个机器可读、人类友好的端点：
 * <pre>
 * GET /v1/info
 * {
 *   "name": "agent-gateway",
 *   "version": "0.3.0",
 *   "buildTimestamp": "2026-09-05T07:43:08Z",
 *   "uptimeSeconds": 12345,
 *   "javaVersion": "21.0.x",
 *   "features": {
 *     "demo": true,
 *     "signup": true,
 *     "oidc": false,
 *     "multiTenantOidc": false
 *   }
 * }
 * </pre>
 *
 * <p>无鉴权；只暴露非敏感元数据。版本/buildTime 来自 jar manifest（mvn 注入）；
 * OIDC 启用/多租户 SaaS OIDC 从 OIDCService 读。
 */
@RestController
public class InfoController {

    private final OIDCService oidcService;

    public InfoController(OIDCService oidcService) {
        this.oidcService = oidcService;
    }

    @GetMapping("/v1/info")
    public Map<String, Object> info() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", "agent-gateway");
        out.put("version", readVersion());
        out.put("buildTimestamp", readBuildTime());
        out.put("uptimeSeconds", uptimeSeconds());
        out.put("javaVersion", System.getProperty("java.version", "unknown"));

        Map<String, Object> features = new LinkedHashMap<>();
        features.put("demo", true);                // demo 模式由 gateway.demo.* 控制
        features.put("signup", true);              // /signup 自助注册总可用
        features.put("oidc", oidcService.isEnabled());
        features.put("multiTenantOidc",
                oidcService.isEnabled() && oidcService.tenantOverrideCount() > 0);
        out.put("features", features);

        // spec §info-endpoint round 37 — 集成方自动跟踪更新用
        out.put("analyticsEnabled", false);        // 占位：可读 env 启用
        out.put("changelogUrl", "/v1/changelog");
        out.put("githubUrl", "https://github.com/tlbfpx/agent-gateway");

        return out;
    }

    private static String readVersion() {
        String v = InfoController.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }

    private static String readBuildTime() {
        String t = InfoController.class.getPackage().getImplementationTitle();
        return t != null ? t : Instant.now().toString();
    }

    private static long uptimeSeconds() {
        long startMs = ManagementFactory.getRuntimeMXBean().getStartTime();
        return (System.currentTimeMillis() - startMs) / 1000;
    }
}