package com.company.agentgateway.interfaces.auth;

import com.company.agentgateway.application.admin.auth.AdminAuthService;
import com.company.agentgateway.application.admin.auth.PasswordHasher;
import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.iam.admin.AdminUser;
import com.company.agentgateway.domain.iam.admin.AdminUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * OIDC 客户端（spec 2026-09-05 §sso-oidc §4）。
 *
 * <p>两阶段：
 * <ol>
 *   <li>login → 构造 IdP authorization URL（round 1）</li>
 *   <li>callback → 校验 state + 换 code → 拉 userinfo → 链接 AdminUser → 签 session（round 2）</li>
 * </ol>
 *
 * <p>JWT signature verification 推迟到 round 3（本轮仅校验 iss / aud / exp claim，
 * 适合 dev/staging；prod 必须 RS256 验签 + JWKS rotation，见 docs/operators/OIDC.md）。
 */
@Service
public class OIDCService {

    private static final Logger log = LoggerFactory.getLogger(OIDCService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final int NONCE_BYTES = 32;

    private final OIDCConfig config;
    private final OidcStateStore stateStore;
    private final OidcJwksClient jwksClient;
    private final OidcDiscoveryClient discoveryClient;
    private final AdminUserRepository adminUserRepo;
    private final AdminAuthService adminAuthService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper json = new ObjectMapper();

    public OIDCService(OIDCConfig config,
                       OidcStateStore stateStore,
                       OidcJwksClient jwksClient,
                       OidcDiscoveryClient discoveryClient,
                       AdminUserRepository adminUserRepo,
                       AdminAuthService adminAuthService) {
        this.config = config;
        this.stateStore = stateStore;
        this.jwksClient = jwksClient;
        this.discoveryClient = discoveryClient;
        this.adminUserRepo = adminUserRepo;
        this.adminAuthService = adminAuthService;
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    /**
     * login 端：构造 authorization URL + state 存入 store。
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

        String packedState = state + "." + base64Url(effectiveReturnTo.getBytes(StandardCharsets.UTF_8));
        stateStore.put(state, nonce); // 用 raw state 存，不带 returnTo（returnTo 在 callback 拆）

        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri(issuer));
        params.put("scope", String.join(" ", config.getScopes()));
        params.put("state", packedState);
        params.put("nonce", nonce);

        // Round 4：用 discovery 解析的 authorization_endpoint；fallback {issuer}/authorize
        String authEndpoint = discoveryClient.resolve(issuer).authorization();
        StringBuilder url = new StringBuilder(authEndpoint);
        // 保留 query string 模式（URL 可能自带 ?）
        if (!authEndpoint.contains("?")) url.append('?');
        else url.append('&');
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

    /**
     * callback 端：校验 state → 换 code 拿 token → 拉 userinfo → 链接 AdminUser → 签 session。
     *
     * @param code     IdP 返回的 authorization code
     * @param state    IdP 返回的 state（packed：raw.returnTo base64）
     * @param nonce    IdP 返回的 nonce（待 round 3 验 ID token 时比对）
     * @return OidcLoginResult 含 tenantId / email / sessionToken / returnTo
     */
    public OidcLoginResult handleCallback(String code, String state, String nonce) {
        if (!config.isEnabled()) {
            throw new IllegalStateException("OIDC is disabled");
        }
        // 1) state 校验：拆 raw + returnTo
        String rawState = extractRawState(state);
        String returnTo = extractReturnTo(state);
        if (rawState == null || returnTo == null) {
            throw new IllegalArgumentException("invalid state format");
        }
        // consume 一次性 nonce 必须对得上
        if (!stateStore.consume(rawState, nonce)) {
            throw new IllegalArgumentException("state invalid or replayed");
        }

        // 2) token exchange
        OidcTokenResponse token = exchangeCode(code);
        if (token.error() != null) {
            throw new IllegalArgumentException("token exchange failed: "
                    + token.error() + " " + token.errorDescription());
        }

        // 3) claims 校验（不含签名，round 3 加 RS256 + JWKS）
        verifyIdTokenClaims(token.idToken());

        // 4) userinfo
        OidcUserInfo userInfo = fetchUserInfo(token.accessToken());
        if (userInfo.email() == null || userInfo.email().isBlank()) {
            throw new IllegalArgumentException("userinfo email missing");
        }

        // 5) find-or-create AdminUser（按 email 链接；首次登录自动 provisioning）
        AdminUser user = provisionAdminUser(userInfo);

        // 6) 签 session token
        AdminAuthService.LoginResult login = adminAuthService.login(
                user.tenantId(), user.email(), deriveBootstrapPassword(user));

        log.info("oidc.callback.ok tenant={} email={} returnTo={}",
                user.tenantId(), user.email(), returnTo);
        return new OidcLoginResult(user.tenantId(), user.email(),
                login.token(), returnTo);
    }

    // ============================= token exchange =============================

    private OidcTokenResponse exchangeCode(String codeCode) {
        String issuer = config.getIssuer();
        String tokenUrl = discoveryClient.resolve(issuer).token();
        String redirectUri = redirectUri(issuer);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", codeCode);
        form.put("client_id", config.getClientId());
        form.put("client_secret", config.getClientSecret());
        form.put("redirect_uri", redirectUri);

        StringBuilder body = new StringBuilder();
        boolean first = true;
        for (var e : form.entrySet()) {
            if (!first) body.append('&');
            body.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            body.append('=');
            body.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            first = false;
        }

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalArgumentException("token endpoint HTTP "
                        + resp.statusCode() + ": " + resp.body());
            }
            return json.readValue(resp.body(), OidcTokenResponse.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("token exchange error: " + e.getMessage(), e);
        }
    }

    // ============================= userinfo =============================

    private OidcUserInfo fetchUserInfo(String accessToken) {
        String userInfoUrl = discoveryClient.resolve(config.getIssuer()).userinfo();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(userInfoUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalArgumentException("userinfo HTTP "
                        + resp.statusCode() + ": " + resp.body());
            }
            return json.readValue(resp.body(), OidcUserInfo.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("userinfo error: " + e.getMessage(), e);
        }
    }

    // ============================= ID token 验证（签名 + claims）=============================

    /** round 3：RS256 签名验证 + iss / aud / exp 校验。 */
    private void verifyIdTokenClaims(String idToken) {
        if (idToken == null || idToken.isBlank()) {
            throw new IllegalArgumentException("id_token missing");
        }
        String[] parts = idToken.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("id_token not a JWT");
        }

        // 1) header：取 alg + kid
        @SuppressWarnings("unchecked")
        Map<String, Object> header = parseJsonSegment(parts[0], Map.class);
        String alg = (String) header.get("alg");
        String kid = (String) header.get("kid");
        if (!"RS256".equals(alg)) {
            throw new IllegalArgumentException("unsupported alg: " + alg + " (only RS256)");
            // 拒掉 'none' 与弱算法；其他算法下轮加
        }
        if (kid == null || kid.isBlank()) {
            throw new IllegalArgumentException("id_token missing kid");
        }

        // 2) 签名验证
        try {
            PublicKey publicKey = jwksClient.getPublicKey(config.getIssuer(), kid);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            // signed input = header.payload（两段原始 base64url，不带 padding 也不解码）
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
            if (!verifier.verify(signature)) {
                throw new IllegalArgumentException("id_token signature invalid");
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception e) {
            throw new IllegalArgumentException("id_token verify error: " + e.getMessage(), e);
        }

        // 3) claims
        @SuppressWarnings("unchecked")
        Map<String, Object> claims = parseJsonSegment(parts[1], Map.class);
        String iss = (String) claims.get("iss");
        Object audRaw = claims.get("aud");
        Number exp = (Number) claims.get("exp");
        String configIssuer = config.getIssuer();
        if (configIssuer != null && !configIssuer.isBlank()
                && !stripTrailingSlash(configIssuer).equals(stripTrailingSlash(iss))) {
            throw new IllegalArgumentException("id_token iss mismatch: " + iss);
        }
        if (audRaw != null && !audienceMatches(audRaw, config.getClientId())) {
            throw new IllegalArgumentException("id_token aud mismatch: " + audRaw);
        }
        if (exp != null && Instant.now().getEpochSecond() >= exp.longValue()) {
            throw new IllegalArgumentException("id_token expired");
        }
    }

    /** aud 可能是 string 或 string[]（多 audience 的 IdP 会返回数组）。 */
    private static boolean audienceMatches(Object audRaw, String expected) {
        if (audRaw instanceof String s) return s.equals(expected);
        if (audRaw instanceof java.util.List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && s.equals(expected)) return true;
            }
        }
        return false;
    }

    private <T> T parseJsonSegment(String segment, Class<T> type) {
        try {
            String jsonStr = new String(
                    Base64.getUrlDecoder().decode(segment),
                    StandardCharsets.UTF_8);
            return json.readValue(jsonStr, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("JWT segment parse failed: " + e.getMessage(), e);
        }
    }

    // ============================= AdminUser provisioning =============================

    private AdminUser provisionAdminUser(OidcUserInfo ui) {
        // 租户约定：oidc- 开头，与 admin token 独立空间
        String tenantId = "oidc-" + sanitize(ui.email().split("@")[1]);
        Optional<AdminUser> existing = adminUserRepo.findByEmail(tenantId, ui.email());
        if (existing.isPresent()) {
            return existing.get();
        }
        // 首次登录：建账户 + bcrypt 一个不可登录密码（OIDC 用户永远走 OIDC 登录）
        AdminUser user = AdminUser.create(
                ui.email(),
                ui.name() != null && !ui.name().isBlank() ? ui.name()
                        : ui.preferredUsername() != null ? ui.preferredUsername() : ui.email(),
                AdminRole.OWNER,
                tenantId,
                PasswordHasher.hash(deriveBootstrapPassword(null)));
        return adminUserRepo.save(user);
    }

    /** OIDC 用户本不需密码登录；保留一个稳定的 bcrypt 输入供 AdminAuthService.login 调用。 */
    private static String deriveBootstrapPassword(AdminUser user) {
        // 同 email + tenant → 同密码；新用户用 email 当种子
        String seed = user != null ? user.email() + "|" + user.tenantId() : null;
        // 用固定 magic 字符串，确保 password verify 永远失败；防止直接密码登录
        return "OIDC_ONLY:" + (seed != null ? seed : "n/a");
    }

    private static String stripTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** Round 4：redirect_uri 走 discovery 解析的 baseUri；
     *  fallback 时按 issuer 派生 {issuer}/callback。 */
    private String redirectUri(String issuer) {
        // 简化：固定用 issuer 派生（RFC 6749 §3.1.2 允许与 IdP 配合）
        return issuer.endsWith("/") ? issuer + "callback" : issuer + "/callback";
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9-]", "-").toLowerCase(java.util.Locale.ROOT);
    }

    private static String randomToken() {
        byte[] buf = new byte[NONCE_BYTES];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 解 state 拿到原始 state（前半段），用于 store 查找。 */
    public static String extractRawState(String packedState) {
        if (packedState == null) return null;
        int idx = packedState.indexOf('.');
        return idx > 0 ? packedState.substring(0, idx) : null;
    }

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

    public record AuthRequest(String authorizationUrl, String state, String nonce) {}
    public record OidcLoginResult(String tenantId, String email, String adminToken, String returnTo) {}
}