package com.company.agentgateway.interfaces.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OIDC REST 入口（spec 2026-09-05 §sso-oidc §6）。
 *
 * <ul>
 *   <li>{@code GET /v1/auth/oidc/login?returnTo=/dashboard} — 构造 authorization URL</li>
 *   <li>{@code GET /v1/auth/oidc/callback?code=&state=&nonce=} — IdP 回调，处理 token exchange + 链接 AdminUser</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/auth/oidc")
public class OIDCController {

    private final OIDCService oidcService;

    public OIDCController(OIDCService oidcService) {
        this.oidcService = oidcService;
    }

    @GetMapping("/login")
    public Map<String, Object> login(
            @RequestParam(value = "returnTo", required = false) String returnTo) {
        ensureEnabled();
        try {
            OIDCService.AuthRequest req = oidcService.buildAuthorizationRequest(returnTo);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("authorizationUrl", req.authorizationUrl());
            out.put("state", req.state());
            out.put("nonce", req.nonce());
            out.put("returnTo", returnTo != null ? returnTo : "/");
            return out;
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    /**
     * IdP 回调端。生产应该用前端 SPA 接：浏览器先到本端点拿 session，
     * 拿完 set-cookie + 302 到 returnTo；或前端用 fetch + 自行 set localStorage。
     */
    @GetMapping("/callback")
    public Map<String, Object> callback(
            @RequestParam("code") String code,
            @RequestParam("state") String state,
            @RequestParam(value = "nonce", required = false) String nonce) {
        ensureEnabled();
        try {
            OIDCService.OidcLoginResult r = oidcService.handleCallback(code, state, nonce);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tenantId", r.tenantId());
            out.put("email", r.email());
            out.put("adminToken", r.adminToken());
            out.put("returnTo", r.returnTo());
            return out;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        }
    }

    private void ensureEnabled() {
        if (!oidcService.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OIDC is disabled");
        }
    }
}