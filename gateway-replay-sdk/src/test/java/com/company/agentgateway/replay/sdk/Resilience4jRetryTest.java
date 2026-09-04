package com.company.agentgateway.replay.sdk;

import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P5.3 — Resilience4jRetry 桥接层单元测试。
 * 覆盖两条 {@code executeWithRetry} 重载 + {@code isTransient} 异常分类。
 */
class Resilience4jRetryTest {

    // =============================================================
    // executeWithRetry(provider, ts, method, path, body)
    //   默认 RetryConfig(3 attempts / 100ms-5s 指数退避 / 25% 抖动)
    // =============================================================

    @Test
    @DisplayName("默认配置:首次成功 → 直接返回签名")
    void executeWithRetrySuccess() {
        CallbackVerifier.SignProvider provider = (a, b, c, d) -> "deadbeef";
        assertThat(Resilience4jRetry.executeWithRetry(provider,
                System.currentTimeMillis(), "POST", "/v1/cb", "body"))
                .isEqualTo("deadbeef");
    }

    @Test
    @DisplayName("默认配置:瞬时异常 → 指数退避重试 → 最终成功")
    void executeWithRetryTransientRecovery() {
        AtomicInteger attempts = new AtomicInteger();
        CallbackVerifier.SignProvider flaky = (a, b, c, d) -> {
            int n = attempts.incrementAndGet();
            if (n < 3) {
                // SocketTimeoutException 沿 cause 链被识别为 transient
                throw new RuntimeException(new SocketTimeoutException("simulated"));
            }
            return "cafebabe";
        };
        long start = System.currentTimeMillis();
        String result = Resilience4jRetry.executeWithRetry(flaky,
                System.currentTimeMillis(), "POST", "/v1/cb", "body");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result).isEqualTo("cafebabe");
        assertThat(attempts.get()).isEqualTo(3); // 2 次失败 + 第 3 次成功
        // 至少等待首次 backoff(100ms 起步);留 50ms 余量
        assertThat(elapsed).isGreaterThanOrEqualTo(100 - 50);
    }

    @Test
    @DisplayName("默认配置:重试耗尽 → 抛 RuntimeException")
    void executeWithRetryExhausted() {
        CallbackVerifier.SignProvider alwaysFail = (a, b, c, d) -> {
            throw new RuntimeException(new IOException("always"));
        };
        assertThatThrownBy(() ->
                Resilience4jRetry.executeWithRetry(alwaysFail,
                        System.currentTimeMillis(), "POST", "/v1/cb", "body"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("默认配置:非瞬时异常 → 立即失败,不重试(无 backoff 等待)")
    void executeWithRetryNonTransientNoRetry() {
        AtomicInteger attempts = new AtomicInteger();
        CallbackVerifier.SignProvider bad = (a, b, c, d) -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("permanent");
        };
        long start = System.currentTimeMillis();
        assertThatThrownBy(() ->
                Resilience4jRetry.executeWithRetry(bad,
                        System.currentTimeMillis(), "POST", "/v1/cb", "body"))
                .isInstanceOf(RuntimeException.class);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(attempts.get()).isEqualTo(1);  // 仅尝试一次
        assertThat(elapsed).isLessThan(100);      // 无 backoff
    }

    // =============================================================
    // executeWithRetry(provider, ts, method, path, body, registry)
    //   共享 RetryRegistry(供 Spring 配置中心统一管理)
    // =============================================================

    @Test
    @DisplayName("共享 RetryRegistry:首次成功")
    void executeWithRetryWithRegistrySuccess() {
        RetryRegistry registry = RetryRegistry.ofDefaults();
        CallbackVerifier.SignProvider provider = (a, b, c, d) -> "feedface";
        assertThat(Resilience4jRetry.executeWithRetry(provider,
                System.currentTimeMillis(), "POST", "/v1/cb", "body", registry))
                .isEqualTo("feedface");
    }

    @Test
    @DisplayName("共享 RetryRegistry:瞬时异常 → 重试 → 最终成功")
    void executeWithRetryWithRegistryTransient() {
        RetryRegistry registry = RetryRegistry.ofDefaults();
        AtomicInteger attempts = new AtomicInteger();
        CallbackVerifier.SignProvider flaky = (a, b, c, d) -> {
            if (attempts.incrementAndGet() < 2) {
                throw new RuntimeException(new ConnectException("refused"));
            }
            return "ok";
        };
        assertThat(Resilience4jRetry.executeWithRetry(flaky,
                System.currentTimeMillis(), "POST", "/v1/cb", "body", registry))
                .isEqualTo("ok");
    }

    @Test
    @DisplayName("共享 RetryRegistry:重试耗尽 → 抛 RuntimeException")
    void executeWithRetryWithRegistryExhausted() {
        RetryRegistry registry = RetryRegistry.ofDefaults();
        CallbackVerifier.SignProvider alwaysFail = (a, b, c, d) -> {
            throw new RuntimeException(new ConnectException("refused"));
        };
        assertThatThrownBy(() -> Resilience4jRetry.executeWithRetry(alwaysFail,
                System.currentTimeMillis(), "POST", "/v1/cb", "body", registry))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("共享 RetryRegistry:多次调用复用同一 retry instance + metrics")
    void sharedRegistryReuseMetrics() {
        RetryRegistry registry = RetryRegistry.ofDefaults();
        CallbackVerifier.SignProvider ok = (a, b, c, d) -> "x";

        Resilience4jRetry.executeWithRetry(ok,
                System.currentTimeMillis(), "POST", "/v1/cb", "body", registry);
        Resilience4jRetry.executeWithRetry(ok,
                System.currentTimeMillis(), "POST", "/v1/cb", "body", registry);

        // 第二次调用应复用 registry 缓存的 'callback-verify' 实例
        var retry = registry.retry("callback-verify");
        assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithoutRetryAttempt())
                .isGreaterThanOrEqualTo(2);
    }

    // =============================================================
    // isTransient(Throwable)
    //   沿 cause 链查找 IOException / SocketTimeout / ConnectException /
    //   Network / Timeout 类名,深度上限 10 层
    // =============================================================

    @Test
    @DisplayName("isTransient:transient 异常识别(直抛)")
    void isTransientDetectsDirectTransient() {
        assertThat(Resilience4jRetry.isTransient(new IOException("io"))).isTrue();
        assertThat(Resilience4jRetry.isTransient(new SocketTimeoutException("sock"))).isTrue();
        assertThat(Resilience4jRetry.isTransient(new ConnectException("conn"))).isTrue();
        assertThat(Resilience4jRetry.isTransient(new TimeoutException("to"))).isTrue();
    }

    @Test
    @DisplayName("isTransient:沿 cause 链查找包装异常")
    void isTransientWalksCauseChain() {
        assertThat(Resilience4jRetry.isTransient(
                new RuntimeException(new IOException("wrapped")))).isTrue();
        assertThat(Resilience4jRetry.isTransient(
                new RuntimeException(new RuntimeException(new SocketTimeoutException("deep"))))).isTrue();
    }

    @Test
    @DisplayName("isTransient:非 transient 异常 → false")
    void isTransientRejectsNonTransient() {
        assertThat(Resilience4jRetry.isTransient(new IllegalStateException("perm"))).isFalse();
        assertThat(Resilience4jRetry.isTransient(new IllegalArgumentException("arg"))).isFalse();
        assertThat(Resilience4jRetry.isTransient(new NullPointerException())).isFalse();
        assertThat(Resilience4jRetry.isTransient(new RuntimeException())).isFalse();
    }

    @Test
    @DisplayName("isTransient:null → false(不抛 NPE)")
    void isTransientNullSafe() {
        assertThat(Resilience4jRetry.isTransient(null)).isFalse();
    }

    @Test
    @DisplayName("isTransient:cause 链深度上限 10(超过即停止扫描)")
    void isTransientDepthLimit() {
        Throwable deep = new IOException("leaf");
        for (int i = 0; i < 20; i++) {
            deep = new RuntimeException(deep);
        }
        assertThat(Resilience4jRetry.isTransient(deep)).isFalse();
    }
}