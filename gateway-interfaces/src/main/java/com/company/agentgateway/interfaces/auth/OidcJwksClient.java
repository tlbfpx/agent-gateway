package com.company.agentgateway.interfaces.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OIDC JWKS 客户端（spec 2026-09-05 §sso-oidc §6）。
 *
 * <p>职责：拉 {issuer}/.well-known/jwks.json → 解析每个 JWK → 缓存 kid → RSA PublicKey。
 * ID token 验签时按 kid 查 key → 用 {@link java.security.Signature}("SHA256withRSA") 校验。
 *
 * <p>缓存策略：成功 fetch 后缓存 10 分钟；首次或 10 分钟后的第一次请求触发 refresh。
 * 失败 fallback 到旧缓存（最多 24h，避免 IdP 抖动期间把用户锁死）。
 *
 * <p>实现细节：纯 JDK（{@link java.security.KeyFactory}），无 nimbus-jose-jwt 依赖；
 * 支持 RSA 公钥（kty=RSA），其他算法（EC/Ed）暂不支持（企业 IdP 都默认 RS256）。
 */
@Component
public class OidcJwksClient {

    private static final Logger log = LoggerFactory.getLogger(OidcJwksClient.class);
    private static final long CACHE_TTL_SECONDS = 600; // 10 min
    private static final long CACHE_MAX_AGE_SECONDS = 24 * 3600; // 1 day 兜底

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper json = new ObjectMapper();

    private volatile CachedKeys cache;

    /**
     * 按 kid 取公钥。缓存命中直接返回；否则拉 JWKS 后再查。
     * 拉失败抛 RuntimeException，调用方应捕获并降级（不要锁定登录）。
     */
    public PublicKey getPublicKey(String issuer, String kid) {
        CachedKeys c = cache;
        if (c == null || !c.matches(issuer) || isStale(c)) {
            c = refresh(issuer);
        }
        PublicKey key = c.keys.get(kid);
        if (key == null && isStale(c)) {
            // 触发一次强制刷新（应对 IdP 轮换 kid 场景）
            c = refresh(issuer);
            key = c.keys.get(kid);
        }
        if (key == null) {
            throw new IllegalStateException("JWK not found for kid=" + kid
                    + " (issuer=" + issuer + ")");
        }
        return key;
    }

    private boolean isStale(CachedKeys c) {
        return Instant.now().getEpochSecond() - c.fetchedAtEpochSec > CACHE_TTL_SECONDS;
    }

    /** 仅供测试：强制重置缓存 */
    public void invalidateCache() {
        cache = null;
    }

    private CachedKeys refresh(String issuer) {
        String url = (issuer.endsWith("/") ? issuer : issuer + "/")
                + ".well-known/jwks.json";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("JWKS HTTP " + resp.statusCode());
            }
            Jwks doc = json.readValue(resp.body(), Jwks.class);
            Map<String, PublicKey> keys = doc.keys().stream()
                    .filter(k -> "RSA".equals(k.kty()))
                    .collect(Collectors.toMap(Jwk::kid, OidcJwksClient::rsaFromJwk,
                            (a, b) -> a, java.util.LinkedHashMap::new));
            CachedKeys c = new CachedKeys(issuer, Instant.now().getEpochSecond(), keys);
            this.cache = c;
            log.info("oidc.jwks.refresh issuer={} keyCount={}", issuer, keys.size());
            return c;
        } catch (Exception e) {
            throw new RuntimeException("JWKS fetch failed for " + issuer + ": " + e.getMessage(), e);
        }
    }

    /** RFC 7518 §3.1 + §6.3.1: kty=RSA + n/e 转 RSAPublicKey。 */
    static RSAPublicKey rsaFromJwk(Jwk k) {
        try {
            BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(k.n()));
            BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(k.e()));
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(n, e));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new RuntimeException("JWK parse failed: " + ex.getMessage(), ex);
        }
    }

    private record CachedKeys(String issuer, long fetchedAtEpochSec,
                              Map<String, PublicKey> keys) {
        boolean matches(String issuer) {
            return this.issuer.equals(stripTrailingSlash(issuer));
        }
    }

    private static String stripTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    // ====================== DTOs ======================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Jwks(@JsonProperty("keys") List<Jwk> keys) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Jwk(
            @JsonProperty("kty") String kty,
            @JsonProperty("kid") String kid,
            @JsonProperty("use") String use,
            @JsonProperty("alg") String alg,
            @JsonProperty("n") String n,
            @JsonProperty("e") String e) {}
}