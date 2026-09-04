package com.company.agentgateway.domain.billing;

import com.company.agentgateway.domain.shared.TenantId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class VirtualKeyTest {

    private static TenantId tenant() {
        return new TenantId("t-1");
    }

    @Test
    void validConstruction_succeeds() {
        VirtualKey vk = new VirtualKey("vk_1", "alice", tenant(), "team-a",
                new BigDecimal("500"), new BigDecimal("100"),
                VirtualKey.Status.ACTIVE, Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(vk.vkId()).isEqualTo("vk_1");
        assertThat(vk.owner()).isEqualTo("alice");
        assertThat(vk.tenant().value()).isEqualTo("t-1");
        assertThat(vk.label()).isEqualTo("team-a");
        assertThat(vk.monthlyQuotaCny()).isEqualByComparingTo("500");
        assertThat(vk.balanceCny()).isEqualByComparingTo("100");
        assertThat(vk.status()).isEqualTo(VirtualKey.Status.ACTIVE);
        assertThat(vk.createdAt()).isNotNull();
    }

    @Test
    void blankVkId_rejected() {
        assertThatThrownBy(() -> new VirtualKey("", "alice", tenant(), "l",
                BigDecimal.ZERO, BigDecimal.ZERO,
                VirtualKey.Status.ACTIVE, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vkId");
    }

    @Test
    void blankOwner_rejected() {
        assertThatThrownBy(() -> new VirtualKey("vk_1", "", tenant(), "l",
                BigDecimal.ZERO, BigDecimal.ZERO,
                VirtualKey.Status.ACTIVE, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner");
    }

    @Test
    void blankLabel_rejected() {
        assertThatThrownBy(() -> new VirtualKey("vk_1", "alice", tenant(), "",
                BigDecimal.ZERO, BigDecimal.ZERO,
                VirtualKey.Status.ACTIVE, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("label");
    }

    @Test
    void negativeMonthlyQuota_rejected() {
        assertThatThrownBy(() -> new VirtualKey("vk_1", "alice", tenant(), "l",
                new BigDecimal("-1"), BigDecimal.ZERO,
                VirtualKey.Status.ACTIVE, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeBalance_rejected() {
        assertThatThrownBy(() -> new VirtualKey("vk_1", "alice", tenant(), "l",
                BigDecimal.ZERO, new BigDecimal("-1"),
                VirtualKey.Status.ACTIVE, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullTenant_rejected() {
        assertThatThrownBy(() -> new VirtualKey("vk_1", "alice", null, "l",
                BigDecimal.ZERO, BigDecimal.ZERO,
                VirtualKey.Status.ACTIVE, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullCreatedAt_rejected() {
        assertThatThrownBy(() -> new VirtualKey("vk_1", "alice", tenant(), "l",
                BigDecimal.ZERO, BigDecimal.ZERO,
                VirtualKey.Status.ACTIVE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statusEnum_hasActiveAndRevoked() {
        assertThat(VirtualKey.Status.values())
                .containsExactly(VirtualKey.Status.ACTIVE, VirtualKey.Status.REVOKED);
    }
}