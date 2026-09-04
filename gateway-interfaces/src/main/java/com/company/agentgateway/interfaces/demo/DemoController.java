package com.company.agentgateway.interfaces.demo;

import com.company.agentgateway.interfaces.demo.DemoService;
import com.company.agentgateway.interfaces.demo.DemoSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    private void ensureEnabled() {
        if (!demoService.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "demo mode disabled");
        }
    }
}