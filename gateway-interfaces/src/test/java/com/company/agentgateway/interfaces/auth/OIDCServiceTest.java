package com.company.agentgateway.interfaces.auth;

import com.company.agentgateway.application.admin.auth.AdminAuthService;
import com.company.agentgateway.domain.iam.admin.AdminUser;
import com.company.agentgateway.domain.iam.admin.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OIDCService 单元测试（spec 2026-09-05 §sso-oidc §7）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>login 端：disabled/issuer/clientId 缺失、URL 参数、state 包 returnTo</li>
 *   <li>callback 端：state malformed 防御</li>
 *   <li>签名验证：合法 RS256 token 通过；alg='none' / 篡改 / aud 不匹配 / exp 过期 / kid 缺失 全部拒掉</li>
 * </ul>
 */
class OIDCServiceTest {

    private OIDCConfig config;
    private OidcStateStore stateStore;
    private OidcJwksClient jwksClient;
    private OidcDiscoveryClient discoveryClient;
    private AdminUserRepository adminUserRepo;
    private AdminAuthService adminAuthService;
    private OIDCService oidc;

    @BeforeEach
    void setUp() {
        config = new OIDCConfig();
        config.setEnabled(true);
        config.setIssuer("https://login.example.com");
        config.setClientId("test-client");
        config.setClientSecret("test-secret");
        config.setScopes(List.of("openid", "email"));
        config.setDefaultRedirectReturnTo("/");
        stateStore = mock(OidcStateStore.class);
        jwksClient = mock(OidcJwksClient.class);
        discoveryClient = mock(OidcDiscoveryClient.class);
        // 默认 discovery fallback 到 issuer 派生（模拟 IdP 不开 discovery）
        when(discoveryClient.resolve(anyString())).thenAnswer(inv -> {
            String issuer = inv.getArgument(0);
            String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
            return new OidcDiscoveryClient.Endpoints(
                    base + "/authorize", base + "/token", base + "/userinfo",
                    base + "/.well-known/jwks.json", true);
        });
        adminUserRepo = mock(AdminUserRepository.class);
        adminAuthService = mock(AdminAuthService.class);
        oidc = new OIDCService(config, stateStore, jwksClient, discoveryClient,
                adminUserRepo, adminAuthService);
    }

    // ====================== login 端 ======================

    @Test
    void disabledThrows() {
        config.setEnabled(false);
        assertThatThrownBy(() -> oidc.buildAuthorizationRequest("/dashboard"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void missingIssuerThrows() {
        config.setIssuer("");
        assertThatThrownBy(() -> oidc.buildAuthorizationRequest(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer");
    }

    @Test
    void missingClientIdThrows() {
        config.setClientId("");
        assertThatThrownBy(() -> oidc.buildAuthorizationRequest(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client");
    }

    @Test
    void authUrlContainsRequiredParameters() {
        OIDCService.AuthRequest req = oidc.buildAuthorizationRequest("/dashboard");
        assertThat(req.authorizationUrl()).startsWith("https://login.example.com/authorize?");
        assertThat(req.authorizationUrl()).contains("response_type=code");
        assertThat(req.authorizationUrl()).contains("client_id=test-client");
        assertThat(req.authorizationUrl()).contains("scope=openid+email");
        assertThat(req.authorizationUrl()).contains("state=");
        assertThat(req.authorizationUrl()).contains("nonce=");
        assertThat(req.state()).isNotBlank();
        assertThat(req.nonce()).isNotBlank();
    }

    @Test
    void stateEncodesReturnTo() {
        OIDCService.AuthRequest req = oidc.buildAuthorizationRequest("/admin-users");
        int idx = req.state().indexOf('.');
        assertThat(idx).isGreaterThan(0);
        assertThat(OIDCService.extractReturnTo(req.state())).isEqualTo("/admin-users");
    }

    @Test
    void defaultReturnToUsedWhenNull() {
        OIDCService.AuthRequest req = oidc.buildAuthorizationRequest(null);
        assertThat(OIDCService.extractReturnTo(req.state())).isEqualTo("/");
    }

    @Test
    void eachRequestProducesDifferentStateAndNonce() {
        var a = oidc.buildAuthorizationRequest("/x");
        var b = oidc.buildAuthorizationRequest("/x");
        assertThat(a.state()).isNotEqualTo(b.state());
        assertThat(a.nonce()).isNotEqualTo(b.nonce());
    }

    @Test
    void extractReturnToReturnsNullOnMalformed() {
        assertThat(OIDCService.extractReturnTo(null)).isNull();
        assertThat(OIDCService.extractReturnTo("no-dot")).isNull();
        assertThat(OIDCService.extractReturnTo("prefix.")).isNull();
    }

    @Test
    void extractRawStateSplitsAtFirstDot() {
        assertThat(OIDCService.extractRawState("abc.def")).isEqualTo("abc");
        assertThat(OIDCService.extractRawState("abc.def.ghi")).isEqualTo("abc");
        assertThat(OIDCService.extractRawState("no-dot")).isNull();
        assertThat(OIDCService.extractRawState(null)).isNull();
        assertThat(OIDCService.extractRawState(".suffix")).isNull();
    }

    // ====================== callback 端（防御）======================

    @Test
    void callbackDisabledThrows() {
        config.setEnabled(false);
        assertThatThrownBy(() -> oidc.handleCallback("code", "raw.nonce", "n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void callbackInvalidStateThrows() {
        when(stateStore.consume(anyString(), anyString())).thenReturn(false);
        assertThatThrownBy(() -> oidc.handleCallback("c", "malformed", "n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ====================== signature verification 单元测试 ======================

    @Test
    void rs256SignatureAcceptsValidToken() throws Exception {
        KeyPair pair = newRsa();
        String kid = "k1";
        when(jwksClient.getPublicKey(eq("https://login.example.com"), eq(kid)))
                .thenReturn(pair.getPublic());

        Map<String, Object> payload = standardPayload();
        String jwt = sign(pair.getPrivate(), kid, payload);

        invokeVerify(jwt); // 不抛 = 通过
    }

    @Test
    void rs256RejectsTamperedPayload() throws Exception {
        KeyPair pair = newRsa();
        String kid = "k1";
        when(jwksClient.getPublicKey(eq("https://login.example.com"), eq(kid)))
                .thenReturn(pair.getPublic());

        Map<String, Object> payload = standardPayload();
        String jwt = sign(pair.getPrivate(), kid, payload);

        // 篡改 payload：换 email + 重新 base64，但保留原 signature → 验签失败
        String[] parts = jwt.split("\\.");
        String tamperedPayload = base64Url("{\"iss\":\"https://login.example.com\",\"aud\":\"test-client\",\"email\":\"attacker@evil.io\"}"
                .getBytes(StandardCharsets.UTF_8));
        String tamperedJwt = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThatThrownBy(() -> invokeVerify(tamperedJwt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void rs256RejectsWrongAudience() throws Exception {
        KeyPair pair = newRsa();
        String kid = "k1";
        when(jwksClient.getPublicKey(anyString(), anyString())).thenReturn(pair.getPublic());

        Map<String, Object> payload = standardPayload();
        payload.put("aud", "other-client");
        String jwt = sign(pair.getPrivate(), kid, payload);

        assertThatThrownBy(() -> invokeVerify(jwt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aud");
    }

    @Test
    void rs256AcceptsAudienceArrayContainingExpectedClient() throws Exception {
        KeyPair pair = newRsa();
        String kid = "k1";
        when(jwksClient.getPublicKey(anyString(), anyString())).thenReturn(pair.getPublic());

        Map<String, Object> payload = standardPayload();
        payload.put("aud", List.of("other-client", "test-client")); // 数组里包含自己
        String jwt = sign(pair.getPrivate(), kid, payload);

        invokeVerify(jwt); // 不抛 = 通过
    }

    @Test
    void rs256RejectsExpiredToken() throws Exception {
        KeyPair pair = newRsa();
        String kid = "k1";
        when(jwksClient.getPublicKey(anyString(), anyString())).thenReturn(pair.getPublic());

        Map<String, Object> payload = standardPayload();
        payload.put("exp", Instant.now().minusSeconds(60).getEpochSecond());
        String jwt = sign(pair.getPrivate(), kid, payload);

        assertThatThrownBy(() -> invokeVerify(jwt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void rs256RejectsNoneAlg() throws Exception {
        // alg=none 必拒；不需要 keypair。空 3rd segment 模拟了典型 none token。
        Map<String, Object> header = Map.of("alg", "none", "typ", "JWT", "kid", "k1");
        Map<String, Object> payload = standardPayload();
        String jwt = base64Url(toJson(header).getBytes(StandardCharsets.UTF_8))
                + "." + base64Url(toJson(payload).getBytes(StandardCharsets.UTF_8))
                + ".AAAA"; // dummy non-empty sig 让 JWT 结构合法；alg 检查先于 sig

        assertThatThrownBy(() -> invokeVerify(jwt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alg");
    }

    @Test
    void rs256RejectsWrongIssuer() throws Exception {
        KeyPair pair = newRsa();
        String kid = "k1";
        when(jwksClient.getPublicKey(anyString(), anyString())).thenReturn(pair.getPublic());

        Map<String, Object> payload = standardPayload();
        payload.put("iss", "https://attacker.example.com");
        String jwt = sign(pair.getPrivate(), kid, payload);

        assertThatThrownBy(() -> invokeVerify(jwt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("iss");
    }

    @Test
    void rs256RejectsMissingKid() throws Exception {
        // kid 缺失 → 直接 401，不走 JWKS 查表
        Map<String, Object> header = Map.of("alg", "RS256", "typ", "JWT");
        Map<String, Object> payload = standardPayload();
        KeyPair pair = newRsa();
        String jwt = sign(pair.getPrivate(), null, header, payload);

        assertThatThrownBy(() -> invokeVerify(jwt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kid");
    }

    // ====================== helpers ======================

    private static KeyPair newRsa() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    /** 标准 claims：iss=配置 issuer，aud=test-client，sub/email/exp/iat。 */
    private Map<String, Object> standardPayload() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("iss", "https://login.example.com");
        m.put("aud", "test-client");
        m.put("sub", "user-1");
        m.put("email", "alice@acme.io");
        Instant now = Instant.now();
        m.put("exp", now.plusSeconds(3600).getEpochSecond());
        m.put("iat", now.getEpochSecond());
        return m;
    }

    private String sign(java.security.PrivateKey priv, String kid,
                        Map<String, Object> payload) throws Exception {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        if (kid != null) header.put("kid", kid);
        return sign(priv, kid, header, payload);
    }

    private String sign(java.security.PrivateKey priv, String kid,
                        Map<String, Object> header, Map<String, Object> payload) throws Exception {
        String headerB64 = base64Url(toJson(header).getBytes(StandardCharsets.UTF_8));
        String payloadB64 = base64Url(toJson(payload).getBytes(StandardCharsets.UTF_8));
        String input = headerB64 + "." + payloadB64;
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(priv);
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        byte[] sig = signer.sign();
        return input + "." + base64Url(sig);
    }

    private static String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof String s) sb.append('"').append(s).append('"');
            else if (v instanceof Number n) sb.append(n);
            else if (v instanceof java.util.List<?> list) {
                sb.append('[');
                boolean lf = true;
                for (Object o : list) {
                    if (!lf) sb.append(',');
                    lf = false;
                    if (o instanceof String s) sb.append('"').append(s).append('"');
                    else sb.append(o);
                }
                sb.append(']');
            } else sb.append(v);
        }
        sb.append('}');
        return sb.toString();
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void invokeVerify(String jwt) {
        try {
            java.lang.reflect.Method m = OIDCService.class
                    .getDeclaredMethod("verifyIdTokenClaims", String.class);
            m.setAccessible(true);
            m.invoke(oidc, jwt);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}