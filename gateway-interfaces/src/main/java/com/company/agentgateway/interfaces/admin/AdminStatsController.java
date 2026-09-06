package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.interfaces.auth.SignupService;
import com.company.agentgateway.interfaces.demo.DemoService;
import com.company.agentgateway.interfaces.info.InfoController;
import com.company.agentgateway.infra.security.ApiKeyStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运营统计端点（spec §business-stats round 39）。
 *
 * <p>GET /v1/admin/stats（需 X-Admin-Token 鉴权）返回：
 * <ul>
 *   <li>{@code demoCount} — 累计 demo bootstrap 次数（重启清零）</li>
 *   <li>{@code signupCount} — 累计自助注册次数（重启清零）</li>
 *   <li>{@code uptimeSeconds} — 进程启动至今秒数</li>
 *   <li>{@code version} — jar 版本</li>
 *   <li>{@code startTime} — ISO-8601 启动时间</li>
 * </ul>
 *
 * <p>给 Sales 「试用频次」数据 + SRE 「进程运行时间」数据。
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminStatsController {

    private final DemoService demoService;
    private final SignupService signupService;
    private final ApiKeyStore apiKeyStore;

    public AdminStatsController(DemoService demoService, SignupService signupService,
                               ApiKeyStore apiKeyStore) {
        this.demoService = demoService;
        this.signupService = signupService;
        this.apiKeyStore = apiKeyStore;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken) {
        // 简化鉴权：任意非空 token 即放行；生产应跟其他 /v1/admin/** 共享 filter
        if (adminToken == null || adminToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Admin-Token required");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("demoCount", demoService.getBootstrapCount());
        out.put("signupCount", signupService.getSignupCount());
        out.put("activeApiKeys", apiKeyStore.entries().size());
        out.put("uptimeSeconds", uptimeSeconds());
        out.put("version", InfoController.class.getPackage().getImplementationVersion());
        out.put("startTime", Instant.ofEpochMilli(
                ManagementFactory.getRuntimeMXBean().getStartTime()).toString());
        return out;
    }

    /**
     * Prometheus 抓取端点（spec §business-stats round 40）：
     *   GET /v1/admin/stats/prom -H "X-Admin-Token: ..."
     *
     * <p>返回 text/plain Prometheus exposition 格式；Grafana / 监控系统可直接 scrape。
     * 单调计数器（demo_count / signup_count）抓取累加值。
     */
    @GetMapping(value = "/stats/prom", produces = "text/plain;version=0.0.4;charset=utf-8")
    public String statsProm(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken) {
        if (adminToken == null || adminToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Admin-Token required");
        }
        long demo = demoService.getBootstrapCount();
        long signup = signupService.getSignupCount();
        long activeKeys = apiKeyStore.entries().size();
        long up = uptimeSeconds();
        return "# HELP agent_gateway_demo_count_total Total demo bootstrap count\n"
                + "# TYPE agent_gateway_demo_count_total counter\n"
                + "agent_gateway_demo_count_total " + demo + "\n"
                + "# HELP agent_gateway_signup_count_total Total signup count\n"
                + "# TYPE agent_gateway_signup_count_total counter\n"
                + "agent_gateway_signup_count_total " + signup + "\n"
                + "# HELP agent_gateway_active_api_keys Current number of active API keys\n"
                + "# TYPE agent_gateway_active_api_keys gauge\n"
                + "agent_gateway_active_api_keys " + activeKeys + "\n"
                + "# HELP agent_gateway_uptime_seconds Process uptime in seconds\n"
                + "# TYPE agent_gateway_uptime_seconds gauge\n"
                + "agent_gateway_uptime_seconds " + up + "\n";
    }

    private static long uptimeSeconds() {
        return (System.currentTimeMillis()
                - ManagementFactory.getRuntimeMXBean().getStartTime()) / 1000;
    }
}