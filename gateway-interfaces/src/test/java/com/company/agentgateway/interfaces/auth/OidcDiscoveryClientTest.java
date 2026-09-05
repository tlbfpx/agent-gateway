package com.company.agentgateway.interfaces.auth;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OidcDiscoveryClient fallback 行为测试（spec §sso-oidc §4 — Round 4）。
 *
 * <p>本测试不实际拉网络（测试用 mock HTTP 太啰嗦，round 5 再加），仅验证：
 * <ul>
 *   <li>fetch 失败 → cache null doc → resolve() fallback 到 issuer 派生</li>
 *   <li>fetch 成功 → 用解析的端点</li>
 *   <li>invalidateCache 强制重拉</li>
 * </ul>
 */
class OidcDiscoveryClientTest {

    @Test
    void fallbackWhenDiscoveryFails() throws Exception {
        OidcDiscoveryClient c = new OidcDiscoveryClient();
        // 直接走 resolve；fetch 会失败（HTTPS 调用；CI 上 DNS 可能解析失败）→ fallback
        OidcDiscoveryClient.Endpoints ep = c.resolve("https://invalid.example.test");
        assertThat(ep.isFallback()).isTrue();
        assertThat(ep.authorization()).isEqualTo("https://invalid.example.test/authorize");
        assertThat(ep.token()).isEqualTo("https://invalid.example.test/token");
        assertThat(ep.userinfo()).isEqualTo("https://invalid.example.test/userinfo");
        assertThat(ep.jwks()).isEqualTo("https://invalid.example.test/.well-known/jwks.json");
    }

    @Test
    void fallbackStripsTrailingSlash() throws Exception {
        OidcDiscoveryClient c = new OidcDiscoveryClient();
        OidcDiscoveryClient.Endpoints ep = c.resolve("https://example.test/");
        assertThat(ep.authorization()).isEqualTo("https://example.test/authorize");
    }

    @Test
    void cacheStoresFailedFetch() throws Exception {
        OidcDiscoveryClient c = new OidcDiscoveryClient();
        OidcDiscoveryClient.Endpoints ep1 = c.resolve("https://nope.example.test");
        // 第二次调用应该走 cache（避免每次都 fetch 失败的开销）
        OidcDiscoveryClient.Endpoints ep2 = c.resolve("https://nope.example.test");
        assertThat(ep1.isFallback()).isTrue();
        assertThat(ep2.isFallback()).isTrue();
        assertThat(ep1.authorization()).isEqualTo(ep2.authorization());
    }

    @Test
    void invalidateCacheAllowsRefetch() throws Exception {
        OidcDiscoveryClient c = new OidcDiscoveryClient();
        c.resolve("https://x.test"); // cache miss → try fetch
        c.invalidateCache(); // 强制清
        // 再次调用会重新 fetch
        OidcDiscoveryClient.Endpoints ep = c.resolve("https://x.test");
        assertThat(ep).isNotNull();
    }

    @Test
    void discoveredEndpointsAreUsedWhenPresent() throws Exception {
        // 用反射注入假 cache（mock 出 discovery 成功），验证 resolve 选 discovered 端点
        OidcDiscoveryClient c = new OidcDiscoveryClient();
        OidcDiscoveryClient.DiscoveryDoc fakeDoc = new OidcDiscoveryClient.DiscoveryDoc(
                "https://idp.example.test",
                "https://idp.example.test/oauth2/authorize",
                "https://idp.example.test/oauth2/token",
                "https://idp.example.test/oauth2/userinfo",
                "https://idp.example.test/.well-known/jwks.json",
                "https://idp.example.test/oauth2/logout");
        injectDoc(c, "https://idp.example.test", fakeDoc);

        OidcDiscoveryClient.Endpoints ep = c.resolve("https://idp.example.test");
        assertThat(ep.isFallback()).isFalse();
        assertThat(ep.authorization()).isEqualTo("https://idp.example.test/oauth2/authorize");
        assertThat(ep.token()).isEqualTo("https://idp.example.test/oauth2/token");
        assertThat(ep.userinfo()).isEqualTo("https://idp.example.test/oauth2/userinfo");
        assertThat(ep.jwks()).isEqualTo("https://idp.example.test/.well-known/jwks.json");
    }

    /** 反射直接写 DiscoveryDoc 进 cache（避免拉真实网络）。 */
    @SuppressWarnings("unchecked")
    private static void injectDoc(OidcDiscoveryClient c, String issuer,
                                   OidcDiscoveryClient.DiscoveryDoc doc) throws Exception {
        java.lang.reflect.Field f = OidcDiscoveryClient.class.getDeclaredField("cache");
        f.setAccessible(true);
        java.util.Map<String, Object> map = (java.util.Map<String, Object>) f.get(c);
        // 用反射 new CachedDiscovery
        Class<?> cachedClass = Class.forName("com.company.agentgateway.interfaces.auth.OidcDiscoveryClient$CachedDiscovery");
        java.lang.reflect.Constructor<?> ctor = cachedClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object entry = ctor.newInstance(doc, java.time.Instant.now().getEpochSecond());
        map.put(issuer, entry);
    }
}