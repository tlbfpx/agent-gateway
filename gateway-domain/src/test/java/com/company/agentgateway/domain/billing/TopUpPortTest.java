package com.company.agentgateway.domain.billing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class TopUpPortTest {

    @Test
    void checkoutSession_validConstruction() {
        TopUpPort.CheckoutSession s = new TopUpPort.CheckoutSession(
                "https://checkout.stripe.com/c/test_x", "cs_test_y",
                new BigDecimal("99.50"));
        assertThat(s.checkoutUrl()).startsWith("https://checkout.stripe.com/");
        assertThat(s.sessionId()).startsWith("cs_test_");
        assertThat(s.amountCny()).isEqualByComparingTo("99.50");
    }

    @Test
    void checkoutSession_blankUrl_rejected() {
        assertThatThrownBy(() -> new TopUpPort.CheckoutSession(
                "", "cs_test_y", BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checkoutUrl");
    }

    @Test
    void checkoutSession_blankSessionId_rejected() {
        assertThatThrownBy(() -> new TopUpPort.CheckoutSession(
                "https://x", "", BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId");
    }

    @Test
    void checkoutSession_negativeAmount_rejected() {
        assertThatThrownBy(() -> new TopUpPort.CheckoutSession(
                "https://x", "cs_test_y", new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stripeEvent_validConstruction() {
        TopUpPort.StripeEvent ev = new TopUpPort.StripeEvent(
                "checkout.session.completed", "cs_test_z", "vk_1",
                new BigDecimal("50"), Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(ev.type()).isEqualTo("checkout.session.completed");
        assertThat(ev.sessionId()).isEqualTo("cs_test_z");
        assertThat(ev.vkId()).isEqualTo("vk_1");
        assertThat(ev.amountCny()).isEqualByComparingTo("50");
        assertThat(ev.occurredAt()).isNotNull();
    }

    @Test
    void stripeEvent_blankType_rejected() {
        assertThatThrownBy(() -> new TopUpPort.StripeEvent(
                "", "cs_test_z", "vk_1", BigDecimal.TEN, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stripeEvent_blankSessionId_rejected() {
        assertThatThrownBy(() -> new TopUpPort.StripeEvent(
                "checkout.session.completed", "", "vk_1", BigDecimal.TEN, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stripeEvent_negativeAmount_rejected() {
        assertThatThrownBy(() -> new TopUpPort.StripeEvent(
                "checkout.session.completed", "cs_test_z", "vk_1",
                new BigDecimal("-1"), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}