package com.company.agentgateway.replay.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallbackVerifierTest {

    private final CallbackVerifier verifier = new CallbackVerifier("shared-secret-32-bytes-long-yes-yes");

    @Test
    @DisplayName("合法签名:通过")
    void validSignature() {
        long ts = System.currentTimeMillis();
        String sig = verifier.sign(ts, "POST", "/v1/cb", "{\"k\":\"v\"}");
        assertThat(verifier.verify(String.valueOf(ts), sig,
                "POST", "/v1/cb", "{\"k\":\"v\"}")).isNull();
    }

    @Test
    @DisplayName("合法签名(带 sha256= 前缀):通过")
    void validSignatureWithPrefix() {
        long ts = System.currentTimeMillis();
        String sig = "sha256=" + verifier.sign(ts, "POST", "/v1/cb", "body");
        assertThat(verifier.verify(String.valueOf(ts), sig,
                "POST", "/v1/cb", "body")).isNull();
    }

    @Test
    @DisplayName("错误 body:拒绝")
    void wrongBody() {
        long ts = System.currentTimeMillis();
        String sig = verifier.sign(ts, "POST", "/v1/cb", "body");
        String err = verifier.verify(String.valueOf(ts), sig,
                "POST", "/v1/cb", "tampered");
        assertThat(err).contains("signature mismatch");
    }

    @Test
    @DisplayName("超过 5 分钟时间戳:拒绝")
    void staleTimestamp() {
        long oldTs = System.currentTimeMillis() - 10 * 60 * 1000L;
        String sig = verifier.sign(oldTs, "POST", "/v1/cb", "body");
        String err = verifier.verify(String.valueOf(oldTs), sig,
                "POST", "/v1/cb", "body");
        assertThat(err).contains("timestamp out of window");
    }

    @Test
    @DisplayName("非法 timestamp 字符串:拒绝")
    void invalidTimestamp() {
        String err = verifier.verify("not-a-number", "deadbeef",
                "POST", "/v1/cb", "body");
        assertThat(err).isEqualTo("invalid timestamp");
    }

    @Test
    @DisplayName("缺失 signature:拒绝")
    void missingSignature() {
        String err = verifier.verify(String.valueOf(System.currentTimeMillis()), null,
                "POST", "/v1/cb", "body");
        assertThat(err).isEqualTo("missing signature");
    }

    @Test
    @DisplayName("空密钥构造:抛 IllegalArgumentException")
    void emptySecretRejected() {
        assertThatThrownBy(() -> new CallbackVerifier(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallbackVerifier(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("constantTimeEquals:防 timing attack")
    void constantTimeEquality() {
        assertThat(CallbackVerifier.constantTimeEquals("abc", "abc")).isTrue();
        assertThat(CallbackVerifier.constantTimeEquals("abc", "abd")).isFalse();
        assertThat(CallbackVerifier.constantTimeEquals("abc", "ab")).isFalse();
        assertThat(CallbackVerifier.constantTimeEquals(null, "abc")).isFalse();
    }

    @Test
    @DisplayName("P4.2:RetryPolicy 校验:maxAttempts>=1 / multiplier>=1 / jitter [0,1]")
    void retryPolicyValidation() {
        assertThatThrownBy(() -> new CallbackVerifier.RetryPolicy(0, 100, 5000, 2, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallbackVerifier.RetryPolicy(3, -1, 100, 2, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallbackVerifier.RetryPolicy(3, 100, 50, 2, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallbackVerifier.RetryPolicy(3, 100, 1000, 0.5, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallbackVerifier.RetryPolicy(3, 100, 1000, 2, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        // 合法构造
        assertThat(new CallbackVerifier.RetryPolicy(3, 100, 5000, 2, 0.25)).isNotNull();
    }

    @Test
    @DisplayName("P4.2:verifyWithRetry 无 policy = 走单次 verify 路径")
    void verifyWithRetryNoPolicy() {
        long ts = System.currentTimeMillis();
        String sig = verifier.sign(ts, "POST", "/v1/cb", "body");
        assertThat(verifier.verifyWithRetry(String.valueOf(ts), sig,
                "POST", "/v1/cb", "body", null)).isNull();
    }

    @Test
    @DisplayName("P4.2:verifyWithRetry:signature mismatch 不重试,立即返回")
    void verifyWithRetryMismatchNoRetry() {
        long ts = System.currentTimeMillis();
        long start = System.currentTimeMillis();
        String err = verifier.verifyWithRetry(String.valueOf(ts), "bad-signature",
                "POST", "/v1/cb", "body", CallbackVerifier.DEFAULT_RETRY);
        long elapsed = System.currentTimeMillis() - start;
        assertThat(err).isEqualTo("signature mismatch");
        // 不应等任何重试(应该 < 100ms)
        assertThat(elapsed).isLessThan(100);
    }

    @Test
    @DisplayName("P4.2:verifyWithRetry:timestamp out of window 不重试")
    void verifyWithRetryStaleTimestamp() {
        long oldTs = System.currentTimeMillis() - 10 * 60 * 1000L;
        String sig = verifier.sign(oldTs, "POST", "/v1/cb", "body");
        String err = verifier.verifyWithRetry(String.valueOf(oldTs), sig,
                "POST", "/v1/cb", "body", CallbackVerifier.DEFAULT_RETRY);
        assertThat(err).contains("timestamp out of window");
    }

    @Test
    @DisplayName("P4.2:verifyWithRetry:瞬时异常 → 重试 → 最终成功")
    void verifyWithRetryTransientRecovery() {
        long ts = System.currentTimeMillis();
        String validSig = verifier.sign(ts, "POST", "/v1/cb", "body");
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        CallbackVerifier.SignProvider flaky = (a, b, c, d) -> {
            int n = attempts.incrementAndGet();
            if (n < 3) {
                // 注:SignProvider 不声明 checked exception,这里包装为 RuntimeException
                throw new RuntimeException(new java.net.SocketTimeoutException("simulated"));
            }
            return validSig;
        };
        var policy = new CallbackVerifier.RetryPolicy(5, 10, 100, 2, 0);
        String err = verifier.verifyWithRetry(String.valueOf(ts), validSig,
                "POST", "/v1/cb", "body", flaky, policy);
        assertThat(err).isNull();
        assertThat(attempts.get()).isEqualTo(3); // 失败 2 次 + 第 3 次成功
    }

    @Test
    @DisplayName("P4.2:verifyWithRetry:非瞬时异常立即失败(不重试)")
    void verifyWithRetryNonTransient() {
        long ts = System.currentTimeMillis();
        String sig = verifier.sign(ts, "POST", "/v1/cb", "body");
        CallbackVerifier.SignProvider bad = (a, b, c, d) -> {
            throw new IllegalStateException("permanent");
        };
        long start = System.currentTimeMillis();
        String err = verifier.verifyWithRetry(String.valueOf(ts), sig,
                "POST", "/v1/cb", "body", bad, CallbackVerifier.DEFAULT_RETRY);
        long elapsed = System.currentTimeMillis() - start;
        assertThat(err).contains("transient error");
        // 非 transient 不应等任何 backoff
        assertThat(elapsed).isLessThan(50);
    }

    @Test
    @DisplayName("P4.2:verifyWithRetry:重试耗尽返回 exhausted retries")
    void verifyWithRetryExhausted() {
        long ts = System.currentTimeMillis();
        String sig = verifier.sign(ts, "POST", "/v1/cb", "body");
        CallbackVerifier.SignProvider alwaysFail = (a, b, c, d) -> {
            // 包装 checked 为 runtime(SignProvider 不声明 throws)
            throw new RuntimeException(new java.io.IOException("always"));
        };
        var fastPolicy = new CallbackVerifier.RetryPolicy(2, 5, 20, 2, 0);
        String err = verifier.verifyWithRetry(String.valueOf(ts), sig,
                "POST", "/v1/cb", "body", alwaysFail, fastPolicy);
        assertThat(err).contains("exhausted retries");
    }
}