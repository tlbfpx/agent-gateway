package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.application.billing.StripeStubAdapter;
import com.company.agentgateway.domain.billing.BillingPort;
import com.company.agentgateway.domain.billing.InMemoryBillingRepository;
import com.company.agentgateway.domain.billing.UsageRecord;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminVirtualKeyController 端点契约（spec §21.7）。
 */
class AdminVirtualKeyControllerTest {

    private StripeStubAdapter stubAdapter;
    private BillingPort billingPort;
    private MockMvc mockMvc;
    private AdminVirtualKeyController controller;

    @BeforeEach
    void setUp() {
        stubAdapter = new StripeStubAdapter();
        billingPort = new InMemoryBillingRepository();
        controller = new AdminVirtualKeyController(stubAdapter, stubAdapter.repository(), billingPort);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void createVk_missingOwnerReturns400() throws Exception {
        mockMvc.perform(post("/v1/admin/virtual-keys")
                        .contentType("application/json")
                        .content("{\"tenant\":\"t1\",\"label\":\"team\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createVk_succeedsAndReturnsMaskedId() throws Exception {
        mockMvc.perform(post("/v1/admin/virtual-keys")
                        .contentType("application/json")
                        .content("{\"owner\":\"alice\",\"tenant\":\"t1\",\"label\":\"team\",\"monthlyQuotaCny\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vkId").exists())
                .andExpect(jsonPath("$.owner").value("alice"))
                .andExpect(jsonPath("$.tenant").value("t1"));
    }

    @Test
    void list_returnsArray() throws Exception {
        mockMvc.perform(get("/v1/admin/virtual-keys").param("tenant", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getById_notFoundReturns404() throws Exception {
        mockMvc.perform(get("/v1/admin/virtual-keys/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getById_existingReturns200() throws Exception {
        // Seed first
        stubAdapter.repository().save(new com.company.agentgateway.domain.billing.VirtualKey(
                "vk_seed", "alice", new TenantId("t1"), "team-a",
                new BigDecimal("100"), new BigDecimal("50"),
                com.company.agentgateway.domain.billing.VirtualKey.Status.ACTIVE,
                Instant.parse("2026-01-01T00:00:00Z")));

        mockMvc.perform(get("/v1/admin/virtual-keys/vk_seed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vkId").value("vk_seed"));
    }

    @Test
    void revoke_returns204() throws Exception {
        stubAdapter.repository().save(new com.company.agentgateway.domain.billing.VirtualKey(
                "vk_seed", "alice", new TenantId("t1"), "team-a",
                new BigDecimal("100"), new BigDecimal("50"),
                com.company.agentgateway.domain.billing.VirtualKey.Status.ACTIVE,
                Instant.parse("2026-01-01T00:00:00Z")));

        mockMvc.perform(delete("/v1/admin/virtual-keys/vk_seed"))
                .andExpect(status().isNoContent());
    }

    @Test
    void topup_succeeds() throws Exception {
        stubAdapter.repository().save(new com.company.agentgateway.domain.billing.VirtualKey(
                "vk_seed", "alice", new TenantId("t1"), "team-a",
                new BigDecimal("100"), new BigDecimal("50"),
                com.company.agentgateway.domain.billing.VirtualKey.Status.ACTIVE,
                Instant.parse("2026-01-01T00:00:00Z")));

        mockMvc.perform(post("/v1/admin/virtual-keys/vk_seed/topup")
                        .contentType("application/json")
                        .content("{\"amountCny\":250}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutUrl").exists())
                .andExpect(jsonPath("$.sessionId").exists())
                .andExpect(jsonPath("$.amountCny").value(250));
    }

    @Test
    void usage_filtersByTenant() throws Exception {
        TenantId t1 = new TenantId("t1");
        billingPort.recordUsage(new UsageRecord(
                "r1", t1, new UserId("u1"), new ModelId("m1"), "agent",
                Instant.now(), 100, 50, new BigDecimal("0.05"),
                new BigDecimal("0.001"), new BigDecimal("0.002")));
        stubAdapter.repository().save(new com.company.agentgateway.domain.billing.VirtualKey(
                "vk_seed", "alice", t1, "team-a",
                new BigDecimal("100"), new BigDecimal("50"),
                com.company.agentgateway.domain.billing.VirtualKey.Status.ACTIVE,
                Instant.parse("2026-01-01T00:00:00Z")));

        mockMvc.perform(get("/v1/admin/virtual-keys/vk_seed/usage")
                        .param("from", Instant.now().minusSeconds(3600).toString())
                        .param("to", Instant.now().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1));
    }
}