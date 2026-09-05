package com.company.agentgateway.interfaces.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
            @RequestParam(value = "returnTo", required = false) String returnTo,
            @RequestParam(value = "tenant", required = false) String tenant) {
        ensureEnabled();
        try {
            OIDCService.AuthRequest req = oidcService.buildAuthorizationRequest(returnTo, tenant);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("authorizationUrl", req.authorizationUrl());
            out.put("state", req.state());
            out.put("nonce", req.nonce());
            out.put("returnTo", returnTo != null ? returnTo : "/");
            out.put("tenant", tenant);
            return out;
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    /**
     * IdP 回调端。默认浏览器场景：302 redirect 到 {@code returnTo}，token 放 URL
     * fragment（#token=...）→ 前端 /oauth/callback 解析并存 localStorage。
     *
     * <p>API 场景：传 {@code redirect=false} 返回 JSON（机器集成 / 调试用）。
     */
    @GetMapping("/callback")
    public Object callback(
            @RequestParam("code") String code,
            @RequestParam("state") String state,
            @RequestParam(value = "nonce", required = false) String nonce,
            @RequestParam(value = "redirect", defaultValue = "true") boolean redirect,
            @RequestParam(value = "tenant", required = false) String tenant,
            HttpServletResponse httpResp) throws IOException {
        ensureEnabled();
        OIDCService.OidcLoginResult r;
        try {
            r = oidcService.handleCallback(code, state, nonce, tenant);
        } catch (IllegalArgumentException ex) {
            if (redirect) {
                String safeMsg = URLEncoder.encode(ex.getMessage(), StandardCharsets.UTF_8);
                httpResp.sendRedirect("/login?error=" + safeMsg);
                return null;
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        }

        if (redirect) {
            // token 放 URL fragment（不进 HTTP referer / server log）
            String safe = URLEncoder.encode(r.adminToken(), StandardCharsets.UTF_8);
            String tenant = URLEncoder.encode(r.tenantId(), StandardCharsets.UTF_8);
            String email = URLEncoder.encode(r.email(), StandardCharsets.UTF_8);
            String target = "/oauth/callback"
                    + "?returnTo=" + URLEncoder.encode(r.returnTo(), StandardCharsets.UTF_8)
                    + "#token=" + safe + "&tenant=" + tenant + "&email=" + email;
            httpResp.sendRedirect(target);
            return null;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", r.tenantId());
        out.put("email", r.email());
        out.put("adminToken", r.adminToken());
        out.put("returnTo", r.returnTo());
        return out;
    }

    private void ensureEnabled() {
        if (!oidcService.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OIDC is disabled");
        }
    }

    /**
     * OIDC RP-Initiated Logout（spec 2026-09-05 §sso-oidc §6）。
     *
     * <p>前端拿不到 IdP session cookie（浏览器跨域），所以必须服务端 redirect 到
     * IdP 的 end_session_endpoint，IdP 端清完 session 后回调回 returnTo。
     *
     * <p>本地 token 由前端 clear（logout 函数）；IdP session 由本端点触发清理。
     * IdP 端若有 id_token_hint 校验，需要前端先调 /v1/admin/auth/me 拿当前 userinfo
     * 再发起（spec §5.2）—— 本轮先用 post_logout_redirect_uri 简化版。
     */
    @GetMapping("/logout")
    public Map<String, Object> logout(
            @RequestParam(value = "returnTo", required = false) String returnTo) {
        ensureEnabled();
        String endSession = oidcService.discoveryClient()
                .resolve(oidcService.config().getIssuer()).endSession();
        if (endSession == null || endSession.isBlank()) {
            // IdP 没暴露 end_session_endpoint：返回空对象让前端 fallback 到本地 logout
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("endSessionEndpoint", null);
            out.put("reason", "IdP does not advertise end_session_endpoint");
            return out;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("post_logout_redirect_uri", returnTo != null ? returnTo : "/login");
        params.put("client_id", oidcService.config().getClientId());

        StringBuilder url = new StringBuilder(endSession);
        if (!endSession.contains("?")) url.append('?');
        else url.append('&');
        boolean first = true;
        for (var e : params.entrySet()) {
            if (!first) url.append('&');
            url.append(java.net.URLEncoder.encode(e.getKey(),
                    java.nio.charset.StandardCharsets.UTF_8));
            url.append('=');
            url.append(java.net.URLEncoder.encode(e.getValue(),
                    java.nio.charset.StandardCharsets.UTF_8));
            first = false;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("endSessionEndpoint", url.toString());
        return out;
    }
}