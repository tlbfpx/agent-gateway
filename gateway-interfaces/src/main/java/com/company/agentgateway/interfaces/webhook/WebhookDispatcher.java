package com.company.agentgateway.interfaces.webhook;

import com.company.agentgateway.infra.config.JsonFileWebhookStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 出站 Webhook 事件推送（spec §25）。
 *
 * <p>订阅方配置（url + secret + 订阅的事件类型）。投递：HTTP POST + HMAC-SHA256 签名
 * （{@code X-Gateway-Signature} header）。失败指数退避重试（最多 5 次：1s/2s/4s/8s/16s），
 * 仍失败记入死信（本类暴露 deadLetters 供诊断——持久化死信队列二期）。
 *
 * <p><b>Sprint 1 P0 持久化</b>:若注入 {@link JsonFileWebhookStore},订阅列表会被持久化到
 * {@code data/webhooks.json},外部修改该文件经 ConfigReloadBus 触发热重载;若未注入,
 * 行为与改造前一致(纯内存)。
 */
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ATTEMPTS = 5;

    /** 订阅定义。 */
    public record Subscription(String url, String secret, List<String> events) {}

    /** 死信记录（诊断 + 重新投递用；payload 保留原始事件负载）。 */
    public record DeadLetter(String url, String event, int attempts, String lastError,
                             Map<String, Object> payload) {
        public DeadLetter {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }

        /** 向后兼容构造（无 payload → 空 Map）。 */
        public DeadLetter(String url, String event, int attempts, String lastError) {
            this(url, event, attempts, lastError, Map.of());
        }
    }

    private final WebClient webClient;
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> retryState = new ConcurrentHashMap<>(); // 简化：事件级
    private final List<DeadLetter> deadLetters = new CopyOnWriteArrayList<>();

    /** 可选:持久化订阅列表的 store;非 null 时 subscribe/unsubscribe 同步落盘 */
    private volatile JsonFileWebhookStore store;

    public WebhookDispatcher() {
        this.webClient = WebClient.builder().build();
    }

    /**
     * 注入持久化 store(Sprint 1 P0):
     * <ul>
     *   <li>从 store 加载初始订阅列表</li>
     *   <li>subscribe/unsubscribe 同步落 store(原子写盘)</li>
     *   <li>store 检测到文件外部修改后,把新列表同步回内存</li>
     * </ul>
     */
    public void setStore(JsonFileWebhookStore store) {
        this.store = store;
        if (store != null) {
            // 启动时从 store 拉取当前订阅快照
            List<Subscription> initial = store.list().stream()
                    .map(WebhookDispatcher::fromStoreSubscription)
                    .toList();
            this.subscriptions.clear();
            this.subscriptions.addAll(initial);
            // 订阅 store 的变更通知,实现热重载
            store.addChangeListener(subs -> {
                List<Subscription> mapped = subs.stream()
                        .map(WebhookDispatcher::fromStoreSubscription)
                        .toList();
                this.subscriptions.clear();
                this.subscriptions.addAll(mapped);
                log.info("WebhookDispatcher reloaded {} subscriptions from store", mapped.size());
            });
        }
    }

    public void subscribe(Subscription s) {
        subscriptions.add(s);
        if (store != null) {
            store.upsert(toStoreSubscription(s));
        }
    }

    public void unsubscribe(String url) {
        subscriptions.removeIf(s -> s.url().equals(url));
        if (store != null) {
            store.remove(url);
        }
    }

    /** Subscription 形态适配(放在 WebhookDispatcher 层避免反向依赖)。 */
    private static JsonFileWebhookStore.Subscription toStoreSubscription(Subscription s) {
        return new JsonFileWebhookStore.Subscription(s.url(), s.secret(), s.events());
    }

    private static Subscription fromStoreSubscription(JsonFileWebhookStore.Subscription s) {
        return new Subscription(s.url(), s.secret(), s.events());
    }

    public List<Subscription> listSubscriptions() {
        return List.copyOf(subscriptions);
    }

    public List<DeadLetter> deadLetters() {
        return List.copyOf(deadLetters);
    }

    /** 发布事件（异步、非阻塞、投递失败不影响调用方）。 */
    public void publish(String eventType, Map<String, Object> payload) {
        for (Subscription sub : subscriptions) {
            if (!sub.events().contains(eventType) && !sub.events().contains("*")) continue;
            Thread.startVirtualThread(() -> deliver(sub, eventType, payload));
        }
    }

    private void deliver(Subscription sub, String eventType, Map<String, Object> payload) {
        String body = serialize(eventType, payload);
        if (body == null) return;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (send(sub, eventType, body, attempt)) return; // 投递成功
            // 指数退避：1s,2s,4s,8s
            if (attempt < MAX_ATTEMPTS) {
                try { Thread.sleep(1000L << (attempt - 1)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
        recordDeadLetter(new DeadLetter(sub.url(), eventType, MAX_ATTEMPTS, "exhausted retries", payload));
        log.error("webhook to {} for {} moved to dead letter", sub.url(), eventType);
    }

    /**
     * 重新投递死信（运营控制台「重新投递」动作，spec §25.3）。
     *
     * <p>单次投递、不走指数退避重试（attempts 重置为 1）。成功则从死信队列移除并返回
     * {@code true}；失败则把记录写回死信队列（{@code url + event} 为去重键）并返回
     * {@code false}。找不到匹配订阅时直接返回 {@code false}。
     */
    public boolean redeliver(DeadLetter dl) {
        Subscription sub = subscriptions.stream()
                .filter(s -> s.url().equals(dl.url()))
                .findFirst().orElse(null);
        if (sub == null) {
            log.warn("redeliver skipped: no subscription for {}", dl.url());
            return false;
        }
        String body = serialize(dl.event(), dl.payload());
        boolean ok = body != null && send(sub, dl.event(), body, 1);
        removeDeadLetter(dl.url(), dl.event());
        if (!ok) {
            recordDeadLetter(new DeadLetter(dl.url(), dl.event(), 1,
                    "redeliver failed", dl.payload()));
        }
        return ok;
    }

    /** 记录死信（{@code url + event} 去重，避免重复投递失败堆积）。 */
    void recordDeadLetter(DeadLetter dl) {
        removeDeadLetter(dl.url(), dl.event());
        deadLetters.add(dl);
    }

    private void removeDeadLetter(String url, String event) {
        deadLetters.removeIf(d -> d.url().equals(url) && d.event().equals(event));
    }

    /** 单次 HTTP 投递；2xx 返回 true。 */
    private boolean send(Subscription sub, String eventType, String body, int attempt) {
        String signature = hmac(sub.secret(), body);
        try {
            var resp = webClient.post()
                    .uri(URI.create(sub.url()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Gateway-Signature", signature)
                    .header("X-Gateway-Event", eventType)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(10));
            if (resp != null && resp.getStatusCode().is2xxSuccessful()) {
                return true;
            }
            log.warn("webhook {} responded {}", sub.url(),
                    resp != null ? resp.getStatusCode() : "null");
        } catch (Exception e) {
            log.warn("webhook attempt {}/{} to {} failed: {}",
                    attempt, MAX_ATTEMPTS, sub.url(), e.getMessage());
        }
        return false;
    }

    private static String serialize(String eventType, Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(Map.of("event", eventType, "data", payload,
                    "timestamp", java.time.Instant.now().toString()));
        } catch (Exception e) {
            log.warn("webhook serialize failed: {}", e.getMessage());
            return null;
        }
    }

    private static String hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("hmac failed", e);
        }
    }
}
