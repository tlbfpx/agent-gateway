package com.company.agentgateway.application.replay;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Replay callback HMAC 签名器(Sprint 2 P2.2):
 * 给 callback POST 添加防伪签名,接收方可验签 + 校验时间戳防重放。
 *
 * <h2>签名方案</h2>
 * <pre>
 * signature = HMAC-SHA256(secret, timestamp + "\n" + method + "\n" + path + "\n" + body)
 * header:
 *   X-Replay-Signature: sha256=&lt;hex&gt;
 *   X-Replay-Timestamp: &lt;unix epoch ms&gt;
 * </pre>
 *
 * <h2>防重放</h2>
 * <p>接收方应验签 + 检查时间戳与本地时间偏差 ≤ 5 分钟。
 *
 * <h2>密钥</h2>
 * <p>从配置 {@code gateway.replay.callback-secret} 注入;若未设,降级为 payload-key 派生(Sprint 2 P0 已有)。
 */
public final class CallbackSigner {

    public static final String HEADER_SIGNATURE = "X-Replay-Signature";
    public static final String HEADER_TIMESTAMP = "X-Replay-Timestamp";
    public static final long MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L; // 5 min

    private final byte[] secret;

    public CallbackSigner(String secretString) {
        if (secretString == null || secretString.isBlank()) {
            throw new IllegalArgumentException("callback secret required");
        }
        this.secret = derive(secretString);
    }

    /** 派生 32 字节 HMAC key。 */
    private static byte[] derive(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("derive failed", e);
        }
    }

    /**
     * 计算签名 hex 字符串(不含 sha256= 前缀 — 由调用方包装)。
     *
     * @param timestampMs 客户端 unix 毫秒时间戳(用于防重放)
     * @param method      HTTP method(POST / PUT ...)
     * @param path        请求路径(/v1/admin/...)
     * @param body        请求体字符串
     */
    public String sign(long timestampMs, String method, String path, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            String canonical = timestampMs + "\n" + method + "\n" + path + "\n" + (body == null ? "" : body);
            byte[] sig = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sig);
        } catch (Exception e) {
            throw new IllegalStateException("sign failed", e);
        }
    }

    /** 验证签名 + 时间戳。返回 null 表示 OK,返回错误消息表示失败。 */
    public String verify(long timestampMs, String method, String path, String body, String providedHex) {
        long now = Instant.now().toEpochMilli();
        if (Math.abs(now - timestampMs) > MAX_CLOCK_SKEW_MS) {
            return "timestamp out of window (now=" + now + " got=" + timestampMs + ")";
        }
        String expected = sign(timestampMs, method, path, body);
        if (!constantTimeEquals(expected, providedHex)) {
            return "signature mismatch";
        }
        return null;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}