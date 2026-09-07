package com.company.agentgateway.interfaces.demo;

import com.company.agentgateway.interfaces.demo.DemoService;
import com.company.agentgateway.interfaces.demo.DemoSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Demo 模式 REST 端点（spec 2026-09-04 §demo-mode §6）。
 *
 * <ul>
 *   <li>{@code GET /v1/demo/status} — 前端首屏判断是否启用 demo</li>
 *   <li>{@code POST /v1/demo/bootstrap} — 创建 demo 租户并返回凭据</li>
 * </ul>
 *
 * <p>任何端点当 {@code gateway.demo.enabled=false} 时返回 404，避免 prod 暴露入口。
 */
@RestController
@RequestMapping("/v1/demo")
public class DemoController {

    private final DemoService demoService;

    public DemoController(DemoService demoService) {
        this.demoService = demoService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        ensureEnabled();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", true);
        out.put("ttlSeconds", demoService.isEnabled()
                ? java.time.Duration.ofHours(24).getSeconds() // 与 DemoConfig 默认同步；spec §3
                : 0);
        return out;
    }

    @PostMapping("/bootstrap")
    public DemoSession bootstrap() {
        ensureEnabled();
        return demoService.bootstrap();
    }

    /**
     * 重置当前 demo（spec §demo-reset round 78）：
     * - 删除本 demo 租户所有 key
     * - 客户端应清 localStorage 后跳 /demo 重新触发 bootstrap
     */
    @PostMapping("/reset")
    public java.util.Map<String, Object> reset(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        ensureEnabled();
        int removed = 0;
        if (apiKey != null && !apiKey.isBlank()) {
            removed = demoService.revokeByKey(apiKey);
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("removed", removed);
        out.put("message", "Demo data cleared. Reload /demo to start fresh.");
        return out;
    }

    private void ensureEnabled() {
        if (!demoService.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "demo mode disabled");
        }
    }
}