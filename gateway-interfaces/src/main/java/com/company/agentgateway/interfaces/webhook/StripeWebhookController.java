package com.company.agentgateway.interfaces.webhook;

import com.company.agentgateway.domain.billing.StripeCheckoutException;
import com.company.agentgateway.domain.billing.TopUpPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Stripe Webhook 接收端点（spec §21.7）。
 *
 * <p>端点：POST /v1/webhooks/stripe
 *
 * <p>MVP 简化：不做 HMAC 验签，仅校验 Stripe-Signature header 非空；
 * 二期接入真实 SDK 后在 {@link TopUpPort} 实现层做 HMAC。
 *
 * <p>错误映射：StripeCheckoutException → 400；其它 → 500。
 */
@RestController
@RequestMapping("/v1/webhooks/stripe")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final TopUpPort topUpPort;

    public StripeWebhookController(TopUpPort topUpPort) {
        this.topUpPort = topUpPort;
    }

    @PostMapping
    public Map<String, Object> handle(@RequestHeader(value = "Stripe-Signature", required = false) String signature,
                                      @RequestBody(required = false) Map<String, Object> body) {
        if (signature == null || signature.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "missing Stripe-Signature header");
        }
        if (body == null || body.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty payload");
        }
        try {
            String type = stringOf(body.get("type"));
            String sessionId = nestedString(body, "data", "object", "id");
            String vkId = stringOf(body.get("vk_id"));
            BigDecimal amountCny = toBigDecimal(body.get("amount_cny"));
            // 未知事件类型：按 TopUpPort 约定静默接受（避免 Stripe 重试风暴）
            if (type == null || !isSupportedEventType(type)) {
                log.debug("Stripe webhook event type ignored or missing: {}", type);
                return Map.of("received", true, "ignored", true, "type", String.valueOf(type));
            }
            if (sessionId == null || vkId == null || amountCny == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "missing required fields (data.object.id / vk_id / amount_cny)");
            }
            topUpPort.handleStripeEvent(new TopUpPort.StripeEvent(
                    type, sessionId, vkId, amountCny, Instant.now()));
            return Map.of("received", true, "type", type, "sessionId", sessionId);
        } catch (StripeCheckoutException e) {
            log.warn("Stripe webhook rejected: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Stripe webhook failed", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "webhook processing failed: " + e.getMessage());
        }
    }

    private static String stringOf(Object o) {
        if (o == null) return null;
        return o.toString();
    }

    @SuppressWarnings("unchecked")
    private static String nestedString(Map<String, Object> body, String... path) {
        Object cur = body;
        for (String key : path) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(key);
        }
        return stringOf(cur);
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        try {
            return new BigDecimal(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isSupportedEventType(String type) {
        return "checkout.session.completed".equals(type)
                || "checkout.session.async_payment_succeeded".equals(type);
    }
}