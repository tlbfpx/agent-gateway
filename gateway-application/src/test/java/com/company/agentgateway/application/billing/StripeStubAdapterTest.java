package com.company.agentgateway.application.billing;

import com.company.agentgateway.domain.billing.StripeCheckoutException;
import com.company.agentgateway.domain.billing.TopUpPort;
import com.company.agentgateway.domain.billing.VirtualKey;
import com.company.agentgateway.domain.shared.TenantId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class StripeStubAdapterTest {

    private static VirtualKey seededKey(StripeStubAdapter adapter, String vkId, String tenantId) {
        VirtualKey vk = new VirtualKey(vkId, "alice", new TenantId(tenantId), "team-a",
                new BigDecimal("500"), new BigDecimal("100"),
                VirtualKey.Status.ACTIVE, Instant.parse("2026-01-01T00:00:00Z"));
        adapter.repository().save(vk);
        return vk;
    }

    @Test
    void createCheckoutSession_unknownVk_throwsNotFound() {
        StripeStubAdapter adapter = new StripeStubAdapter();
        assertThatThrownBy(() -> adapter.createCheckoutSession("missing",
                new BigDecimal("100")))
                .isInstanceOf(StripeCheckoutException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void createCheckoutSession_zeroAmount_throwsValidation() {
        StripeStubAdapter adapter = new StripeStubAdapter();
        seededKey(adapter, "vk_1", "t-1");
        assertThatThrownBy(() -> adapter.createCheckoutSession("vk_1",
                BigDecimal.ZERO))
                .isInstanceOf(StripeCheckoutException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void createCheckoutSession_valid_returnsStubUrlAndSessionId() {
        StripeStubAdapter adapter = new StripeStubAdapter();
        seededKey(adapter, "vk_1", "t-1");

        TopUpPort.CheckoutSession s = adapter.createCheckoutSession("vk_1",
                new BigDecimal("250"));

        assertThat(s.checkoutUrl()).startsWith("https://checkout.stripe.com/c/test_vk_1");
        assertThat(s.sessionId()).startsWith("cs_test_");
        assertThat(s.amountCny()).isEqualByComparingTo("250");
    }

    @Test
    void handleStripeEvent_completedSession_creditsBalanceAndClearsPending() {
        StripeStubAdapter adapter = new StripeStubAdapter();
        seededKey(adapter, "vk_1", "t-1");
        TopUpPort.CheckoutSession session = adapter.createCheckoutSession("vk_1",
                new BigDecimal("250"));
        VirtualKey before = adapter.repository().findById("vk_1").orElseThrow();
        assertThat(before.balanceCny()).isEqualByComparingTo("100");

        adapter.handleStripeEvent(new TopUpPort.StripeEvent(
                "checkout.session.completed", session.sessionId(), "vk_1",
                new BigDecimal("250"), Instant.parse("2026-02-01T00:00:00Z")));

        VirtualKey after = adapter.repository().findById("vk_1").orElseThrow();
        assertThat(after.balanceCny()).isEqualByComparingTo("350");
        assertThat(after.owner()).isEqualTo("alice");
        assertThat(after.monthlyQuotaCny()).isEqualByComparingTo("500");
        assertThat(after.status()).isEqualTo(VirtualKey.Status.ACTIVE);
    }

    @Test
    void handleStripeEvent_unknownSession_silentlyIgnored() {
        StripeStubAdapter adapter = new StripeStubAdapter();
        seededKey(adapter, "vk_1", "t-1");
        // No pending session created; handler should NOT throw.
        adapter.handleStripeEvent(new TopUpPort.StripeEvent(
                "checkout.session.completed", "cs_test_unknown", "vk_1",
                new BigDecimal("100"), Instant.now()));
        VirtualKey vk = adapter.repository().findById("vk_1").orElseThrow();
        assertThat(vk.balanceCny()).isEqualByComparingTo("100");
    }

    @Test
    void handleStripeEvent_unknownType_silentlyIgnored() {
        StripeStubAdapter adapter = new StripeStubAdapter();
        seededKey(adapter, "vk_1", "t-1");
        TopUpPort.CheckoutSession session = adapter.createCheckoutSession("vk_1",
                new BigDecimal("50"));
        adapter.handleStripeEvent(new TopUpPort.StripeEvent(
                "charge.refunded", session.sessionId(), "vk_1",
                new BigDecimal("50"), Instant.now()));
        // balance unchanged since handler ignored unknown type
        VirtualKey vk = adapter.repository().findById("vk_1").orElseThrow();
        assertThat(vk.balanceCny()).isEqualByComparingTo("100");
    }

    @Test
    void handleStripeEvent_asyncPaymentSucceeded_creditsBalance() {
        StripeStubAdapter adapter = new StripeStubAdapter();
        seededKey(adapter, "vk_1", "t-1");
        TopUpPort.CheckoutSession session = adapter.createCheckoutSession("vk_1",
                new BigDecimal("40"));
        adapter.handleStripeEvent(new TopUpPort.StripeEvent(
                "checkout.session.async_payment_succeeded", session.sessionId(),
                "vk_1", new BigDecimal("40"), Instant.now()));
        VirtualKey vk = adapter.repository().findById("vk_1").orElseThrow();
        assertThat(vk.balanceCny()).isEqualByComparingTo("140");
    }

    @Test
    void repository_exposesInMemoryStore() {
        StripeStubAdapter adapter = new StripeStubAdapter();
        seededKey(adapter, "vk_2", "t-2");
        Optional<VirtualKey> fetched = adapter.repository().findById("vk_2");
        assertThat(fetched).isPresent();
        assertThat(adapter.repository().findByTenant(new TenantId("t-2")))
                .extracting(VirtualKey::vkId)
                .containsExactly("vk_2");
    }

    @Test
    void revoke_thenRevokedStatusVisible() {
        StripeStubAdapter adapter = new StripeStubAdapter();
        seededKey(adapter, "vk_3", "t-3");
        adapter.repository().revoke("vk_3");
        VirtualKey vk = adapter.repository().findById("vk_3").orElseThrow();
        assertThat(vk.status()).isEqualTo(VirtualKey.Status.REVOKED);
    }
}