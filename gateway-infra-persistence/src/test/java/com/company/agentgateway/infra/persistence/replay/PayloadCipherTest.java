package com.company.agentgateway.infra.persistence.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayloadCipherTest {

    private final PayloadCipher cipher = new PayloadCipher("test-key-for-symmetric-encryption-32".getBytes(StandardCharsets.UTF_8));

    @Test
    @DisplayName("encrypt + decrypt:同 key 还原原文")
    void roundTrip() {
        String plain = "hello world 你好世界 — 含 unicode 与 emoji 🚀";
        byte[] enc = cipher.encrypt(plain);
        String dec = cipher.decrypt(enc);
        assertThat(dec).isEqualTo(plain);
    }

    @Test
    @DisplayName("两次 encrypt 同一明文 → 不同密文(IV 随机)")
    void ivRandomization() {
        String plain = "same text";
        byte[] a = cipher.encrypt(plain);
        byte[] b = cipher.encrypt(plain);
        assertThat(a).isNotEqualTo(b);
        // 但两者都能正确解密
        assertThat(cipher.decrypt(a)).isEqualTo(plain);
        assertThat(cipher.decrypt(b)).isEqualTo(plain);
    }

    @Test
    @DisplayName("短 key 抛 IllegalArgumentException")
    void shortKeyRejected() {
        assertThatThrownBy(() -> new PayloadCipher("short".getBytes()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("≥ 16");
    }

    @Test
    @DisplayName("null 明文 → 加密空串,解密空串")
    void nullPlaintext() {
        byte[] enc = cipher.encrypt(null);
        assertThat(cipher.decrypt(enc)).isEmpty();
    }

    @Test
    @DisplayName("密文太短(被截断) → 抛 IllegalStateException")
    void truncatedCiphertextFails() {
        byte[] shortCt = new byte[]{1, 2, 3}; // < 12 IV + 16 tag
        assertThatThrownBy(() -> cipher.decrypt(shortCt))
                .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
    }

    @Test
    @DisplayName("不同 key 还原失败(认证失败)")
    void wrongKeyRejected() {
        PayloadCipher other = new PayloadCipher("different-key-also-long-enough-yes".getBytes(StandardCharsets.UTF_8));
        byte[] enc = cipher.encrypt("secret");
        assertThatThrownBy(() -> other.decrypt(enc))
                .isInstanceOf(IllegalStateException.class);
    }
}