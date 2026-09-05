package com.company.agentgateway.interfaces.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OIDC 客户端（spec 2026-09-05 §sso-oidc §4）。
 *
 * <p>本轮实现：仅负责构造 authorization URL + state；
 * 下轮加 token exchange + userinfo + JWT 验签 + 链接 AdminUser。
 *
 * <p>OIDC Authorization Code Flow 第一步：
 *   1) 生成 state（防 CSRF）+ nonce（防 ID token 重放）
 *   2) 构造 IdP authorization URL（含 redirect_uri / scope / state / nonce）
 *   3) 返回给前端 → 前端 location.href 跳转 IdP 登录页
 *   4) 用户在 IdP 完成登录 → IdP 重定向回 /v1/auth/oidc/callback?code=&state=
 *   5) 下轮实现：校验 state / 用 code 换 token / 验 ID token 签名 / 链接 AdminUser
 */
@Service
public class OIDCService {

    private static final Logger log = LoggerFactory.getLogger(OIDCService.class);
    private static final SecureRandom RNG = new SecureRandom();

    /** state + nonce 字节长度（32 字节 ≈ 43 base64url 字符） */
    private static final int NONCE_BYTES = 32;

    private final OIDCConfig config;

    public OIDCService(OIDCConfig config) {
        this.config = config;
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    /**
     * 构造 IdP authorization URL。
     *
     * @param returnTo 用户登录成功后要返回的相对路径（如 /dashboard）；
     *                 会编码进 state 由 callback 端解码
     * @return AuthRequest{authorizationUrl, state, nonce}；前端 location.href 到 authorizationUrl
     */
    public AuthRequest buildAuthorizationRequest(String returnTo) {
        if (!config.isEnabled()) {
            throw new IllegalStateException("OIDC is disabled");
        }
        String issuer = config.getIssuer();
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("gateway.oidc.issuer not configured");
        }
        String clientId = config.getClientId();
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("gateway.oidc.client-id not configured");
        }
        String state = randomToken();
        String nonce = randomToken();
        String effectiveReturnTo = (returnTo == null || returnTo.isBlank())
                ? config.getDefaultRedirectReturnTo() : returnTo;

        // state = base64url(state) + "." + base64url(returnTo)
        // callback 端把后半段拆出来当 returnTo（防止直接伪造 state 通过重定向）
        String packedState = state + "." + base64Url(effectiveReturnTo.getBytes(StandardCharsets.UTF_8));

        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", clientId);
        params.put("redirect_uri", issuer + "/callback"); // 可配置，本轮简化
        params.put("scope", String.join(" ", config.getScopes()));
        params.put("state", packedState);
        params.put("nonce", nonce);

        StringBuilder url = new StringBuilder(issuer);
        if (!issuer.endsWith("/")) url.append('/');
        url.append("authorize?");
        boolean first = true;
        for (var e : params.entrySet()) {
            if (!first) url.append('&');
            url.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            url.append('=');
            url.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        log.info("oidc.auth.build returnTo={} stateLen={} scopes={}",
                effectiveReturnTo, packedState.length(), config.getScopes());
        return new AuthRequest(url.toString(), packedState, nonce);
    }

    private static String randomToken() {
        byte[] buf = new byte[NONCE_BYTES];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 解 state 拿到原始 returnTo；下轮 callback 用。 */
    public static String extractReturnTo(String packedState) {
        if (packedState == null) return null;
        int idx = packedState.indexOf('.');
        if (idx < 0 || idx == packedState.length() - 1) return null;
        try {
            return new String(
                    Base64.getUrlDecoder().decode(packedState.substring(idx + 1)),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** 返回值包装。 */
    public record AuthRequest(String authorizationUrl, String state, String nonce) {}
}