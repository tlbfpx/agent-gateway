package com.company.agentgateway.infra.persistence.replay;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * AES-256-GCM 加解密工具(Sprint 2 P0):
 * 用于 trace_payloads.body_enc 字段落盘前加密 / 读盘后解密。
 *
 * <h2>密钥来源</h2>
 * <p>由 gateway.replay.payload-key-ref 提供(实际密钥从外部 secret store 拉取;
 * 此处接受 32 字节 raw key 或派生自密码)。
 *
 * <h2>格式</h2>
 * <pre>
 * [12 bytes IV][encrypted body + 16 bytes auth tag]
 * </pre>
 * IV 由 SecureRandom 每次生成(避免重放)。
 */
public final class PayloadCipher {

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();

    private final SecretKey key;

    public PayloadCipher(byte[] rawKey) {
        if (rawKey == null || rawKey.length < 16) {
            throw new IllegalArgumentException("rawKey must be ≥ 16 bytes");
        }
        try {
            // 派生 32 字节 AES-256 key(SHA-256)
            byte[] derived = MessageDigest.getInstance("SHA-256").digest(rawKey);
            this.key = new SecretKeySpec(derived, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("PayloadCipher init failed", e);
        }
    }

    /** 加密:返回 [IV][ciphertext+tag]。 */
    public byte[] encrypt(String plaintext) {
        if (plaintext == null) plaintext = "";
        try {
            byte[] iv = new byte[IV_LEN];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    /** 解密:输入 [IV][ciphertext+tag]。 */
    public String decrypt(byte[] ciphertext) {
        if (ciphertext == null || ciphertext.length < IV_LEN + TAG_BITS / 8) {
            throw new IllegalArgumentException("ciphertext too short");
        }
        try {
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(ciphertext, 0, iv, 0, IV_LEN);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = c.doFinal(ciphertext, IV_LEN, ciphertext.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("decrypt failed", e);
        }
    }
}