package com.company.agentgateway.interfaces.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OIDC Discovery 客户端（spec 2026-09-05 §sso-oidc §4 — Round 4）。
 *
 * <p>读 {issuer}/.well-known/openid-configuration，解析 IdP 实际端点：
 * authorization_endpoint / token_endpoint / userinfo_endpoint / jwks_uri。
 * 让企业客户只需配 issuer + client-id + client-secret，不用手填 4 个端点 URL。
 *
 * <p>缓存：成功 fetch 后缓存 1 小时；key=issuer，value=DiscoveryDoc。
 * 失败 fallback 到 OidcLegacyEndpoints（按 issuer 派生 /authorize 等），
 * 兼容未启用 discovery 的私有 IdP（如 Keycloak 默认关闭 discovery）。
 */
@Component
public class OidcDiscoveryClient {

    private static final Logger log = LoggerFactory.getLogger(OidcDiscoveryClient.class);
    private static final long CACHE_TTL_SECONDS = 3600;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper json = new ObjectMapper();

    private final Map<String, CachedDiscovery> cache = new ConcurrentHashMap<>();

    /** 给定 issuer，返回发现的端点。失败 fallback 到 legacy 派生。 */
    public Endpoints resolve(String issuer) {
        CachedDiscovery c = cache.get(issuer);
        if (c == null || isStale(c)) {
            c = tryFetch(issuer);
            cache.put(issuer, c);
        }
        if (c.doc != null) {
            log.debug("oidc.discovery.hit issuer={}", issuer);
            return new Endpoints(
                    c.doc.authorization_endpoint(),
                    c.doc.token_endpoint(),
                    c.doc.userinfo_endpoint(),
                    c.doc.jwks_uri(),
                    c.doc.end_session_endpoint(),
                    false);
        }
        // Fallback：未发现 / discovery 失败 → 拼 URL
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        log.info("oidc.discovery.fallback issuer={}", issuer);
        return Endpoints.withoutEndSession(
                base + "/authorize",
                base + "/token",
                base + "/userinfo",
                base + "/.well-known/jwks.json",
                true);
    }

    private CachedDiscovery tryFetch(String issuer) {
        String url = (issuer.endsWith("/") ? issuer : issuer + "/")
                + ".well-known/openid-configuration";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("oidc.discovery.fetch.failed issuer={} status={}",
                        issuer, resp.statusCode());
                return new CachedDiscovery(null, Instant.now().getEpochSecond());
            }
            DiscoveryDoc doc = json.readValue(resp.body(), DiscoveryDoc.class);
            log.info("oidc.discovery.ok issuer={} auth={} token={} jwks={}",
                    issuer, doc.authorization_endpoint(), doc.token_endpoint(), doc.jwks_uri());
            return new CachedDiscovery(doc, Instant.now().getEpochSecond());
        } catch (Exception e) {
            log.warn("oidc.discovery.fetch.error issuer={} msg={}", issuer, e.getMessage());
            return new CachedDiscovery(null, Instant.now().getEpochSecond());
        }
    }

    private boolean isStale(CachedDiscovery c) {
        return Instant.now().getEpochSecond() - c.fetchedAtEpochSec > CACHE_TTL_SECONDS;
    }

    /** 强制清缓存（测试用） */
    public void invalidateCache() {
        cache.clear();
    }

    private record CachedDiscovery(DiscoveryDoc doc, long fetchedAtEpochSec) {}

    /** RFC 8414 §2 / OpenID Connect Discovery §4。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DiscoveryDoc(
            @JsonProperty("issuer") String issuer,
            @JsonProperty("authorization_endpoint") String authorization_endpoint,
            @JsonProperty("token_endpoint") String token_endpoint,
            @JsonProperty("userinfo_endpoint") String userinfo_endpoint,
            @JsonProperty("jwks_uri") String jwks_uri,
            @JsonProperty("end_session_endpoint") String end_session_endpoint) {}

    /** 解析后的端点（discovery 或 fallback）。 */
    public record Endpoints(
            String authorization,
            String token,
            String userinfo,
            String jwks,
            String endSession,
            boolean isFallback) {

        public static Endpoints withoutEndSession(String authorization, String token,
                                                  String userinfo, String jwks,
                                                  boolean isFallback) {
            return new Endpoints(authorization, token, userinfo, jwks, null, isFallback);
        }
    }
}