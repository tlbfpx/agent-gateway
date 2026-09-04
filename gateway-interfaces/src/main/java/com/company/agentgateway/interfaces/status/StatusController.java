package com.company.agentgateway.interfaces.status;

import com.company.agentgateway.interfaces.demo.DemoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Status 端点（spec 2026-09-05 §status-page）。
 *
 * <ul>
 *   <li>{@code GET /status.json} — 公开，机器可读（curl/JSON）</li>
 *   <li>{@code GET /status} — 同内容，Content-Type text/html（前端 /status 页直接渲染）</li>
 * </ul>
 *
 * <p>返回字段：
 * <ul>
 *   <li>{@code status} — 总体状态；目前固定 UP（fail-open：单组件 DOWN 不影响整体）</li>
 *   <li>{@code version} — jar ImplementationVersion（CI 通过 mvn 注入）</li>
 *   <li>{@code uptimeSeconds} — 进程启动至今的秒数</li>
 *   <li>{@code services} — 各子系统状态（gateway / demo / postgres）</li>
 * </ul>
 */
@RestController
public class StatusController {

    private static final Instant STARTED_AT = Instant.ofEpochMilli(
            ManagementFactory.getRuntimeMXBean().getStartTime());

    private final DemoService demoService;
    private final String appVersion;
    private final String buildTimestamp;

    public StatusController(DemoService demoService,
                         @Value("${spring.application.name:agent-gateway}") String appName) {
        this.demoService = demoService;
        Package pkg = StatusController.class.getPackage();
        this.appVersion = readVersion(pkg);
        this.buildTimestamp = readBuildTime(pkg);
    }

    @GetMapping(value = "/status.json", produces = "application/json;charset=UTF-8")
    public Map<String, Object> statusJson() {
        return snapshot();
    }

    @GetMapping(value = "/status", produces = "text/html;charset=UTF-8")
    public String statusHtml() {
        Map<String, Object> snap = snapshot();
        // 极简 HTML（前端 /status 页是 admin 视角，会再包一层）；这个端点给运维 curl 用
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\">")
          .append("<title>agent-gateway status</title>")
          .append("<style>body{font-family:ui-monospace,monospace;margin:24px;}h1{font-size:18px;}table{border-collapse:collapse;}td,th{padding:4px 12px;border-bottom:1px solid #ddd;text-align:left;}</style>")
          .append("</head><body>")
          .append("<h1>agent-gateway · ").append(snap.get("status")).append("</h1>")
          .append("<p>version ").append(snap.get("version")).append(" · uptime ")
          .append(snap.get("uptimeSeconds")).append("s</p>")
          .append("<table><tr><th>service</th><th>status</th><th>detail</th></tr>");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> services =
                (Map<String, Map<String, Object>>) snap.get("services");
        services.forEach((k, v) -> sb.append("<tr><td>").append(k).append("</td><td>")
                .append(v.get("status")).append("</td><td>").append(v.get("detail")).append("</td></tr>"));
        sb.append("</table></body></html>");
        return sb.toString();
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "UP");
        out.put("version", appVersion);
        out.put("buildTimestamp", buildTimestamp);
        out.put("uptimeSeconds", (System.currentTimeMillis() - STARTED_AT.toEpochMilli()) / 1000);
        out.put("services", servicesBlock());
        return out;
    }

    private Map<String, Map<String, Object>> servicesBlock() {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        m.put("gateway", entry("UP", "embedded Spring Boot 4 on :8080"));
        m.put("demo", entry(demoService.isEnabled() ? "ENABLED" : "DISABLED",
                demoService.isEnabled() ? "24h TTL demo sessions" : "self-serve signup only"));
        // postgres health：actuator/health 已经覆盖；这里只给个简短状态字符串
        m.put("postgres", entry("UNKNOWN", "see /actuator/health"));
        return m;
    }

    private static Map<String, Object> entry(String status, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("detail", detail);
        return m;
    }

    private static String readVersion(Package pkg) {
        String v = pkg.getImplementationVersion();
        return v != null ? v : "dev";
    }

    private static String readBuildTime(Package pkg) {
        String t = pkg.getImplementationTitle();
        return t != null ? t : "local";
    }
}