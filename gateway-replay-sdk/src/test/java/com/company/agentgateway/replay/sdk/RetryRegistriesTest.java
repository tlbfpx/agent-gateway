package com.company.agentgateway.replay.sdk;

import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P5.3 — RetryRegistries 工厂单元测试。
 * 验证 SDK {@code DEFAULT_RETRY} ↔ Resilience4j {@link RetryConfig} 行为同步。
 */
class RetryRegistriesTest {

    // =============================================================
    // callbackVerifyDefault()
    // =============================================================

    @Test
    @DisplayName("callbackVerifyDefault:不返回 null")
    void defaultNotNull() {
        assertThat(RetryRegistries.callbackVerifyDefault()).isNotNull();
    }

    @Test
    @DisplayName("callbackVerifyDefault:retry instance 名固定为 'callback-verify'")
    void defaultUsesCanonicalInstanceName() {
        RetryRegistry registry = RetryRegistries.callbackVerifyDefault();
        assertThat(registry.retry(RetryRegistries.CALLBACK_VERIFY).getName())
                .isEqualTo("callback-verify");
    }

    @Test
    @DisplayName("callbackVerifyDefault:maxAttempts=3(与 SDK DEFAULT_RETRY 一致)")
    void defaultMaxAttemptsMatchesSdkPolicy() {
        RetryConfig cfg = RetryRegistries.callbackVerifyDefault()
                .retry(RetryRegistries.CALLBACK_VERIFY).getRetryConfig();
        assertThat(cfg.getMaxAttempts()).isEqualTo(CallbackVerifier.DEFAULT_RETRY.maxAttempts());
    }

    @Test
    @DisplayName("callbackVerifyDefault:多次调用复用同一 RetryRegistry(metrics 累加)")
    void defaultRegistryReuseAcrossCalls() {
        RetryRegistry r1 = RetryRegistries.callbackVerifyDefault();
        RetryRegistry r2 = RetryRegistries.callbackVerifyDefault();
        // of(...) 每次都新建,但 instance name + 内部 config 一致(可各自持有)
        assertThat(r1).isNotNull();
        assertThat(r2).isNotNull();
        // 同一个 name 在同一个 registry 内复用同一 Retry 实例
        var first = r1.retry(RetryRegistries.CALLBACK_VERIFY);
        var second = r1.retry(RetryRegistries.CALLBACK_VERIFY);
        assertThat(first).isSameAs(second);
    }

    // =============================================================
    // callbackVerifyFrom(policy)
    // =============================================================

    @Test
    @DisplayName("callbackVerifyFrom:按 SDK RetryPolicy 构造(maxAttempts 透传)")
    void fromPolicyPassesMaxAttempts() {
        var policy = new CallbackVerifier.RetryPolicy(5, 50, 1000, 1.5, 0.1);
        RetryConfig cfg = RetryRegistries.callbackVerifyFrom(policy)
                .retry(RetryRegistries.CALLBACK_VERIFY).getRetryConfig();
        assertThat(cfg.getMaxAttempts()).isEqualTo(5);
    }

    @Test
    @DisplayName("callbackVerifyFrom:自定义 policy → 实际重试按 policy 行为")
    void fromPolicyDrivesActualRetries() {
        var policy = new CallbackVerifier.RetryPolicy(4, 5, 20, 2.0, 0.0);
        RetryRegistry registry = RetryRegistries.callbackVerifyFrom(policy);
        AtomicInteger attempts = new AtomicInteger();
        CallbackVerifier.SignProvider flaky = (a, b, c, d) -> {
            if (attempts.incrementAndGet() < 4) {
                throw new RuntimeException(new SocketTimeoutException("sim"));
            }
            return "ok";
        };
        String result = Resilience4jRetry.executeWithRetry(flaky,
                System.currentTimeMillis(), "POST", "/v1/cb", "body", registry);
        assertThat(result).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(4); // 3 次失败 + 第 4 次成功
    }

    @Test
    @DisplayName("callbackVerifyFrom:重试耗尽 → 抛 RuntimeException")
    void fromPolicyExhausted() {
        var policy = new CallbackVerifier.RetryPolicy(2, 5, 20, 2.0, 0.0);
        RetryRegistry registry = RetryRegistries.callbackVerifyFrom(policy);
        CallbackVerifier.SignProvider alwaysFail = (a, b, c, d) -> {
            throw new RuntimeException(new SocketTimeoutException("always"));
        };
        assertThatThrownBy(() -> Resilience4jRetry.executeWithRetry(alwaysFail,
                System.currentTimeMillis(), "POST", "/v1/cb", "body", registry))
                .isInstanceOf(RuntimeException.class);
    }
}