package com.company.agentgateway.application.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallbackSignerTest {

    private final CallbackSigner signer = new CallbackSigner("test-secret-with-enough-bytes");

    @Test
    @DisplayName("签名前缀 sha256= 可被外部使用")
    void signFormat() {
        String sig = signer.sign(1234567890L, "POST", "/v1/cb", "{\"jobId\":\"x\"}");
        assertThat(sig).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("同输入 → 同签名(确定性)")
    void signDeterministic() {
        String a = signer.sign(1000L, "POST", "/x", "body");
        String b = signer.sign(1000L, "POST", "/x", "body");
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("不同输入 → 不同签名")
    void differentInputsDifferentSignatures() {
        String a = signer.sign(1000L, "POST", "/x", "body");
        String b = signer.sign(1001L, "POST", "/x", "body");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("verify:正确签名 → null(通过)")
    void verifyOk() {
        long ts = System.currentTimeMillis();
        String sig = signer.sign(ts, "POST", "/v1/cb", "{\"k\":\"v\"}");
        assertThat(signer.verify(ts, "POST", "/v1/cb", "{\"k\":\"v\"}", sig)).isNull();
    }

    @Test
    @DisplayName("verify:错误签名 → 拒绝")
    void verifyWrongSignature() {
        String result = signer.verify(System.currentTimeMillis(), "POST", "/x", "body",
                "00000000000000000000000000000000000000000000000000000000deadbeef");
        assertThat(result).contains("signature mismatch");
    }

    @Test
    @DisplayName("verify:超过 5 分钟时间戳 → 拒绝(防重放)")
    void verifyStaleTimestamp() {
        long oldTs = System.currentTimeMillis() - 10 * 60 * 1000L; // 10 分钟前
        String sig = signer.sign(oldTs, "POST", "/x", "body");
        String result = signer.verify(oldTs, "POST", "/x", "body", sig);
        assertThat(result).contains("timestamp out of window");
    }

    @Test
    @DisplayName("空密钥 → IllegalArgumentException")
    void emptySecretRejected() {
        assertThatThrownBy(() -> new CallbackSigner(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallbackSigner(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("不同密钥 → 签名不同")
    void differentSecretsDifferentSignatures() {
        CallbackSigner other = new CallbackSigner("different-secret");
        String ts = "1700000000000";
        String a = signer.sign(1700000000000L, "POST", "/x", "body");
        String b = other.sign(1700000000000L, "POST", "/x", "body");
        assertThat(a).isNotEqualTo(b);
    }
}