package com.company.agentgateway.interfaces.webhook;

import com.company.agentgateway.application.billing.StripeStubAdapter;
import com.company.agentgateway.domain.billing.VirtualKey;
import com.company.agentgateway.domain.shared.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * StripeWebhookController 端点契约（spec §21.7）。
 */
class StripeWebhookControllerTest {

    private StripeStubAdapter stubAdapter;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        stubAdapter = new StripeStubAdapter();
        mockMvc = MockMvcBuilders.standaloneSetup(new StripeWebhookController(stubAdapter)).build();
        // Seed VK + create a pending session
        stubAdapter.repository().save(new VirtualKey(
                "vk_1", "alice", new TenantId("t1"), "team-a",
                new BigDecimal("100"), new BigDecimal("0"),
                VirtualKey.Status.ACTIVE, Instant.parse("2026-01-01T00:00:00Z")));
    }

    @Test
    void completedEvent_creditsBalance() throws Exception {
        var session = stubAdapter.createCheckoutSession("vk_1", new BigDecimal("120"));

        String body = "{\"type\":\"checkout.session.completed\","
                + "\"data\":{\"object\":{\"id\":\"" + session.sessionId() + "\"}},"
                + "\"amount_cny\":120,\"vk_id\":\"vk_1\"}";

        mockMvc.perform(post("/v1/webhooks/stripe")
                        .header("Stripe-Signature", "stub")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        VirtualKey vk = stubAdapter.repository().findById("vk_1").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(vk.balanceCny()).isEqualByComparingTo("120");
    }

    @Test
    void unknownEvent_silentlyAccepted() throws Exception {
        String body = "{\"type\":\"charge.refunded\",\"data\":{\"object\":{\"id\":\"cs_x\"}}}";

        mockMvc.perform(post("/v1/webhooks/stripe")
                        .header("Stripe-Signature", "stub")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ignored").value(true));
    }

    @Test
    void missingSignature_returns400() throws Exception {
        String body = "{\"type\":\"checkout.session.completed\"}";

        mockMvc.perform(post("/v1/webhooks/stripe")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyBody_returns400() throws Exception {
        mockMvc.perform(post("/v1/webhooks/stripe")
                        .header("Stripe-Signature", "stub")
                        .contentType("application/json")
                        .content(""))
                .andExpect(status().isBadRequest());
    }
}