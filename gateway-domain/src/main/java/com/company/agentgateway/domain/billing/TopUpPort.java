package com.company.agentgateway.domain.billing;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 出站端口：Virtual Key 充值（spec §21.7 + D3 设计 §3.2）。
 *
 * <p>由 Stripe / 其它支付通道实现；一期在应用层内嵌 {@code StripeStubAdapter}
 * 模拟 checkout.session.completed 事件 → 入账 balanceCny。
 *
 * <p>职责边界：只负责「开单 + 接收回调入账」；balanceCny 落库由
 * {@link VirtualKeyRepository} 承担。
 */
public interface TopUpPort {

    /**
     * 创建 Checkout Session。
     *
     * @param vkId      目标 VirtualKey（必须已存在）
     * @param amountCny 充值金额（CNY，> 0）
     * @return Stripe checkout session（URL + sessionId + amount）
     * @throws StripeCheckoutException vkId 不存在 / amount 非法 / 网络异常
     */
    CheckoutSession createCheckoutSession(String vkId, BigDecimal amountCny);

    /**
     * 处理 Stripe 回调事件。仅消费
     * {@code checkout.session.completed} 与
     * {@code checkout.session.async_payment_succeeded}，
     * 其它类型静默忽略（不抛异常，避免 Stripe 重试）。
     */
    void handleStripeEvent(StripeEvent event);

    /** Stripe Checkout Session 简化结构。 */
    record CheckoutSession(String checkoutUrl, String sessionId, BigDecimal amountCny) {
        public CheckoutSession {
            if (checkoutUrl == null || checkoutUrl.isBlank()) {
                throw new IllegalArgumentException("checkoutUrl must not be blank");
            }
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("sessionId must not be blank");
            }
            if (amountCny == null || amountCny.signum() < 0) {
                throw new IllegalArgumentException("amountCny must be ≥ 0");
            }
        }
    }

    /** Stripe Webhook 事件负载（最小子集）。 */
    record StripeEvent(String type, String sessionId, String vkId,
                       BigDecimal amountCny, Instant occurredAt) {
        public StripeEvent {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("type must not be blank");
            }
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("sessionId must not be blank");
            }
            if (vkId == null || vkId.isBlank()) {
                throw new IllegalArgumentException("vkId must not be blank");
            }
            if (amountCny == null || amountCny.signum() < 0) {
                throw new IllegalArgumentException("amountCny must be ≥ 0");
            }
            if (occurredAt == null) {
                throw new IllegalArgumentException("occurredAt must not be null");
            }
        }
    }
}