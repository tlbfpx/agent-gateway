package com.company.agentgateway.interfaces.auth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OIDC 状态查询端点（spec 2026-09-05 §sso-oidc §6）。
 *
 * <p>前端启动时 GET /v1/auth/oidc/status 决定是否显示 SSO 按钮。
 * OIDC 未启用时直接 404（前端 catch 后隐藏按钮）。
 */
@RestController
@RequestMapping("/v1/auth/oidc")
public class OIDCStatusController {

    private final OIDCService oidcService;

    public OIDCStatusController(OIDCService oidcService) {
        this.oidcService = oidcService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", oidcService.isEnabled());
        out.put("displayName", "Enterprise SSO");
        return out;
    }
}