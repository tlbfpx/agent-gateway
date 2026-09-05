package com.company.agentgateway.interfaces.auth;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * OIDC state 存储（spec 2026-09-05 §sso-oidc §5）。
 *
 * <p>Callback 端用 state 作为一次性 token：
 * <ul>
 *   <li>login 端：写入 {nonce, createdAt}（防 CSRF + 重放）</li>
 *   <li>callback 端：验证 state 存在且未过期，命中后立即删除</li>
 * </ul>
 *
 * <p>设计权衡：内存 map，单实例够用；多实例 / 重启会丢未消费的 state
 * （用户需重新登录，对 B2B SaaS 可接受；高可用场景下轮换 Redis。
 */
@Component
public class OidcStateStore {

    /** state 默认 10 分钟 TTL（远超 IdP 登录耗时；过期防 replay） */
    private static final long TTL_SECONDS = 600;

    private final ConcurrentMap<String, Entry> states = new ConcurrentHashMap<>();

    public void put(String state, String nonce) {
        states.put(state, new Entry(nonce, Instant.now().plusSeconds(TTL_SECONDS)));
    }

    /** 命中且未过期返回 true 并删除；否则 false。 */
    public boolean consume(String state, String nonce) {
        Entry e = states.remove(state);
        if (e == null) return false;
        if (e.nonce == null || !e.nonce.equals(nonce)) return false;
        return Instant.now().isBefore(e.expiresAt);
    }

    /** 定期清理过期 entry（避免内存漏；规模小不会涨太快） */
    public void evictExpired() {
        Instant now = Instant.now();
        states.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt));
    }

    private record Entry(String nonce, Instant expiresAt) {}
}