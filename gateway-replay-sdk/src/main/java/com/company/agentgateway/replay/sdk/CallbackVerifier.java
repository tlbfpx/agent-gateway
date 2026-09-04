package com.company.agentgateway.replay.sdk;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Agent Gateway Replay Callback 验签 SDK(Sprint 2 P3.1 + P4.2)。
 *
 * <p>下游消费方(运营平台 / 监控 / 第三方 BI)在收到 POST callback 时,使用本类验签:
 * <ol>
 *   <li>读 header {@code X-Replay-Timestamp} 与 {@code X-Replay-Signature}(格式 sha256=&lt;hex&gt;)</li>
 *   <li>调用 {@link #verify},返回 null = 合法,非 null = 错误描述</li>
 *   <li>同一密钥可在 Agent Gateway 端用 {@code CallbackSigner} 计算签名</li>
 * </ol>
 *
 * <h2>用法</h2>
 * <pre>
 *   CallbackVerifier v = new CallbackVerifier(System.getenv("GATEWAY_REPLAY_SECRET"));
 *   String err = v.verify(req.getHeader("X-Replay-Timestamp"),
 *                         req.getHeader("X-Replay-Signature"),
 *                         "POST", req.getPath(), req.getBody());
 *   if (err != null) throw new SecurityException(err);
 * </pre>
 *
 * <h2>协议</h2>
 * <pre>
 *   signature = HMAC-SHA256(secret, timestamp + "\n" + method + "\n" + path + "\n" + body)
 *   timestamp = unix epoch ms;偏差 &le; 5 分钟算有效
 * </pre>
 *
 * <h2>verifyWithRetry(P4.2)</h2>
 * <p>{@link #verify} 是纯计算,无网络调用,通常不需要重试。本类提供
 * {@link #verifyWithRetry} 用于"签名计算外部依赖"的高级场景(如 HSM/KMS),
 * 接受 {@link RetryPolicy} 控制指数退避,仅对瞬时异常重试,
 * 对签名/时间戳等业务错误立即失败(防重放)。
 */
public final class CallbackVerifier {

    public static final long MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L; // 5 min

    /** 默认重试策略:3 次,100ms / 500ms / 2s 指数退避 + 25% 抖动。 */
    public static final RetryPolicy DEFAULT_RETRY =
            new RetryPolicy(3, 100, 5000, 2.0, 0.25);

    private final byte[] secret;

    public CallbackVerifier(String secretString) {
        if (secretString == null || secretString.isBlank()) {
            throw new IllegalArgumentException("callback secret required");
        }
        this.secret = derive(secretString);
    }

    private static byte[] derive(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("derive failed", e);
        }
    }

    /**
     * 计算签名 hex 字符串(不含 sha256= 前缀)。
     * 暴露此方法便于下游在调试时独立验签逻辑。
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

    /**
     * 验签 + 时间戳窗口校验。
     *
     * @param timestampMs Header X-Replay-Timestamp(unix epoch ms,字符串解析)
     * @param signature   Header X-Replay-Signature(可含 sha256= 前缀)
     * @param method      HTTP method(POST / PUT ...)
     * @param path        请求 path(/v1/cb)
     * @param body        请求 body 原文
     * @return null = 通过;非 null = 错误描述
     */
    public String verify(String timestampMs, String signature,
                          String method, String path, String body) {
        return verifyWithRetry(timestampMs, signature, method, path, body, null);
    }

    /**
     * 验签带重试(网络抖动场景,如签名存储在 HSM/KMS 时偶发连接失败)。
     *
     * <p><b>重试策略</b>:
     * <ul>
     *   <li>业务错误(签名不匹配、时间戳无效、签名缺失)— 立即返回,不重试</li>
     *   <li>瞬时异常(IO / Socket / Timeout)— 按 RetryPolicy 指数退避重试</li>
     * </ul>
     *
     * <p>{@code provider} 用于外部签名源(如 HSM);传 null 时走本地 sign()(纯计算,无异常)。
     *
     * @param policy 重试策略;null = 不重试
     */
    public String verifyWithRetry(String timestampMs, String signature,
                                   String method, String path, String body,
                                   SignProvider provider, RetryPolicy policy) {
        long ts;
        try {
            ts = Long.parseLong(timestampMs);
        } catch (Exception e) {
            return "invalid timestamp";
        }
        long now = Instant.now().toEpochMilli();
        if (Math.abs(now - ts) > MAX_CLOCK_SKEW_MS) {
            return "timestamp out of window (now=" + now + " got=" + ts + ")";
        }
        if (signature == null) return "missing signature";
        String provided = signature.startsWith("sha256=")
                ? signature.substring("sha256=".length()) : signature;

        // 业务错误快速失败:signature mismatch 不重试
        String expected;
        try {
            expected = provider != null ? provider.expectedSignature(ts, method, path, body)
                                       : sign(ts, method, path, body);
        } catch (Exception transientErr) {
            // 瞬时异常(网络/HSM 不可用)→ 按 policy 重试
            if (policy == null || !isTransient(transientErr)) {
                return "transient error: " + transientErr.getMessage();
            }
            return retryVerifyWithPolicy(provider, policy, ts, method, path, body, provided);
        }

        if (!constantTimeEquals(expected, provided)) {
            return "signature mismatch";
        }
        return null;
    }

    /** {@link #verifyWithRetry} 的便捷重载,使用默认策略 + 本地 sign。 */
    public String verifyWithRetry(String timestampMs, String signature,
                                   String method, String path, String body, RetryPolicy policy) {
        return verifyWithRetry(timestampMs, signature, method, path, body, null, policy);
    }

    private String retryVerifyWithPolicy(SignProvider provider, RetryPolicy policy,
                                          long ts, String method, String path, String body,
                                          String provided) {
        long delay = policy.initialBackoffMs;
        Throwable last = null;
        for (int attempt = 1; attempt < policy.maxAttempts; attempt++) {
            try {
                Thread.sleep(jitter(delay, policy.jitterRatio));
                String expected = provider != null ? provider.expectedSignature(ts, method, path, body)
                                                  : sign(ts, method, path, body);
                if (constantTimeEquals(expected, provided)) {
                    return null;
                }
                return "signature mismatch";  // 业务错误不重试
            } catch (Exception e) {
                last = e;
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                delay = Math.min((long) (delay * policy.multiplier), policy.maxBackoffMs);
            }
        }
        return "exhausted retries: " + (last != null ? last.getMessage() : "unknown");
    }

    private static long jitter(long baseMs, double jitterRatio) {
        double delta = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * jitterRatio;
        return Math.max(0, (long) (baseMs * (1 + delta)));
    }

    /** 简单瞬时异常识别:IO / Socket / Timeout 类重试(沿 cause 链查找包装异常)。 */
    private static boolean isTransient(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 10) {
            String name = cur.getClass().getName();
            if (name.contains("IOException") || name.contains("SocketTimeout")
                    || name.contains("ConnectException") || name.contains("Network")
                    || name.contains("Timeout")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /** constant-time 字符串比较(防 timing attack)。 */
    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    /** 重试策略(不可变 record)。 */
    public record RetryPolicy(int maxAttempts, long initialBackoffMs,
                               long maxBackoffMs, double multiplier, double jitterRatio) {
        public RetryPolicy {
            if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts >= 1");
            if (initialBackoffMs < 0) throw new IllegalArgumentException("backoff >= 0");
            if (maxBackoffMs < initialBackoffMs) throw new IllegalArgumentException("maxBackoff >= initial");
            if (multiplier < 1) throw new IllegalArgumentException("multiplier >= 1");
            if (jitterRatio < 0 || jitterRatio > 1) throw new IllegalArgumentException("jitterRatio [0, 1]");
        }
    }

    /** 外部签名源(HSM/KMS 等);verify 时调 expectedSignature 取签名。 */
    @FunctionalInterface
    public interface SignProvider {
        String expectedSignature(long ts, String method, String path, String body);
    }
}