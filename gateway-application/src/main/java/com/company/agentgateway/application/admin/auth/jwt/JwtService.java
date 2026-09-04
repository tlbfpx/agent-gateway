package com.company.agentgateway.application.admin.auth.jwt;

import com.company.agentgateway.domain.iam.admin.AdminRole;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JWT 服务（spec 2026-09-02 §jwt-auth §3）。
 *
 * <p>P0 实现：HMAC-SHA256 + base64url(header.payload.signature) 三段式。
 * R15+1 替换为 jjwt(已装在 m2);架构不变,只换实现。
 *
 * <p>Claims：{@code sub(adminId) role tenantId iat exp}。
 */
public class JwtService {

    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final byte[] secret;
    private final long ttlSeconds;

    public JwtService(String secret, long ttlSeconds) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException("secret must be ≥ 16 chars");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(long adminId, AdminRole role, String tenantId) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", String.valueOf(adminId));
        claims.put("role", role.name());
        claims.put("tenantId", tenantId);
        claims.put("iat", Instant.now().getEpochSecond());
        claims.put("exp", Instant.now().getEpochSecond() + ttlSeconds);
        return sign(claims);
    }

    /** 解析 + 验证;返回 claims 或抛异常 */
    public Map<String, Object> verify(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("empty token");
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new IllegalArgumentException("malformed token");
        String signingInput = parts[0] + "." + parts[1];
        String expectedSig = hmac(signingInput);
        if (!constantTimeEquals(expectedSig, parts[2])) {
            throw new SecurityException("invalid signature");
        }
        Map<String, Object> claims = decodeJson(parts[1]);
        long exp = ((Number) claims.getOrDefault("exp", 0L)).longValue();
        if (exp > 0 && Instant.now().getEpochSecond() > exp) {
            throw new SecurityException("token expired");
        }
        return claims;
    }

    private String sign(Map<String, Object> claims) {
        String payload = encodeJson(claims);
        String headerB64 = ENC.encodeToString(HEADER.getBytes(StandardCharsets.UTF_8));
        String payloadB64 = ENC.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signingInput = headerB64 + "." + payloadB64;
        String sig = hmac(signingInput);
        return signingInput + "." + sig;
    }

    private String hmac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] sig = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            return ENC.encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    /** 极简 JSON encode(单层,只支持 string/number);够 claims 用 */
    private static String encodeJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v instanceof Number) sb.append(v);
            else if (v instanceof Boolean) sb.append(v);
            else sb.append("\"").append(escape(v.toString())).append("\"");
        }
        return sb.append("}").toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Map<String, Object> decodeJson(String b64) {
        String json = new String(DEC.decode(b64), StandardCharsets.UTF_8);
        Map<String, Object> out = new HashMap<>();
        // 极简解析:{"k":"v","k2":123,...}
        String body = json.trim();
        if (body.startsWith("{") && body.endsWith("}")) body = body.substring(1, body.length() - 1);
        for (String pair : splitTopLevel(body)) {
            int colon = findUnquotedColon(pair);
            if (colon < 0) continue;
            String k = unquote(pair.substring(0, colon).trim());
            String v = pair.substring(colon + 1).trim();
            // 去掉可能残留的尾部大括号/逗号
            if (v.endsWith("}") || v.endsWith(",")) v = v.substring(0, v.length() - 1).trim();
            if (v.startsWith("\"") && v.endsWith("\"")) {
                out.put(k, unescape(v.substring(1, v.length() - 1)));
            } else {
                try { out.put(k, Long.parseLong(v)); }
                catch (NumberFormatException e) { out.put(k, v); }
            }
        }
        return out;
    }

    private static int findUnquotedColon(String s) {
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inQuote = !inQuote;
            if (c == ':' && !inQuote) return i;
        }
        return -1;
    }

    private static java.util.List<String> splitTopLevel(String s) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        boolean inQuote = false;
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inQuote = !inQuote;
            if (!inQuote) {
                if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') depth--;
                else if (c == ',' && depth == 0) {
                    parts.add(s.substring(start, i));
                    start = i + 1;
                }
            }
        }
        if (start < s.length()) parts.add(s.substring(start));
        return parts;
    }

    private static String unquote(String s) {
        if (s.startsWith("\"") && s.endsWith("\"")) return unescape(s.substring(1, s.length() - 1));
        return s;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}