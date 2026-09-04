package com.company.agentgateway.application.admin.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * 密码哈希工具（spec 2026-09-02 §bcrypt-auth §3）。
 *
 * <p>P0 实现：PBKDF2-HMAC-SHA256 + 16-byte salt + 32-byte hash + 100k 迭代（OWASP 2023 推荐）。
 * 输出 PHC 格式字符串：{@code $pbkdf2-sha256$iter$salt$hash}（base64 URL-safe）。
 *
 * <p>优势：
 * <ul>
 *   <li>JDK 自带(无新依赖)</li>
 *   <li>FIPS / OWASP 推荐</li>
 *   <li>易升级:R15 可换 Argon2id(只需替换此文件)</li>
 * </ul>
 */
public final class PasswordHasher {

    private static final String ALGO = "PBKDF2WithHmacSHA256";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int DEFAULT_ITERATIONS = 100_000;
    private static final String SCHEME = "$pbkdf2-sha256$";

    private PasswordHasher() {}

    /** 生成 PHC 字符串密码哈希;返回 {@code $pbkdf2-sha256$100000$<saltB64>$<hashB64>} */
    public static String hash(String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("password must not be empty");
        }
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] hash = pbkdf2(password.toCharArray(), salt, DEFAULT_ITERATIONS, HASH_BYTES);
        return SCHEME + DEFAULT_ITERATIONS + "$"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(salt) + "$"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    /** 验证明文密码是否匹配 PHC 字符串;时间恒定(防时序攻击) */
    public static boolean verify(String password, String phc) {
        if (password == null || phc == null || phc.isBlank()) return false;
        if (!phc.startsWith(SCHEME)) return false;
        String[] parts = phc.split("\\$");
        // parts[0] = "", parts[1] = "pbkdf2-sha256", parts[2] = "100000", parts[3] = salt, parts[4] = hash
        if (parts.length != 5) return false;
        try {
            int iter = Integer.parseInt(parts[2]);
            byte[] salt = Base64.getUrlDecoder().decode(parts[3]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[4]);
            byte[] actual = pbkdf2(password.toCharArray(), salt, iter, expected.length);
            return MessageDigest.isEqual(actual, expected);
        } catch (Exception ex) {
            return false;
        }
    }

    /** 便捷:用 PHC 字符串作新密码哈希(用于 reset) */
    public static boolean needsRehash(String phc) {
        if (phc == null || !phc.startsWith(SCHEME)) return true;
        String[] parts = phc.split("\\$");
        if (parts.length != 5) return true;
        try {
            return Integer.parseInt(parts[2]) < DEFAULT_ITERATIONS;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iter, int bytes) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iter, bytes * 8);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGO);
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 unavailable: " + e.getMessage(), e);
        }
    }

    private static byte[] randomBytes(int len) {
        try {
            byte[] b = new byte[len];
            SecureRandom.getInstanceStrong().nextBytes(b);
            return b;
        } catch (NoSuchAlgorithmException e) {
            // 兜底:用默认 SecureRandom
            byte[] b = new byte[len];
            new SecureRandom().nextBytes(b);
            return b;
        }
    }
}
