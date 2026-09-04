package com.company.agentgateway.interfaces.demo;

import com.company.agentgateway.application.admin.auth.AdminAuthService;
import com.company.agentgateway.domain.iam.admin.AdminUser;
import com.company.agentgateway.domain.iam.admin.AdminUserRepository;
import com.company.agentgateway.infra.security.ApiKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DemoService 单元测试（spec 2026-09-04 §demo-mode §7）。
 */
class DemoServiceTest {

    private DemoConfig config;
    private ApiKeyStore apiKeyStore;
    private AdminUserRepository adminUserRepo;
    private AdminAuthService adminAuthService;
    private DemoService service;

    @BeforeEach
    void setUp() {
        config = new DemoConfig();
        config.setEnabled(true);
        config.setTtl(Duration.ofHours(24));

        apiKeyStore = mock(ApiKeyStore.class);
        adminUserRepo = mock(AdminUserRepository.class);
        adminAuthService = mock(AdminAuthService.class);

        when(adminUserRepo.save(any(AdminUser.class))).thenAnswer(inv -> {
            AdminUser u = inv.getArgument(0);
            return new AdminUser(42L, u.email(), u.name(), u.role(), u.status(),
                    u.tenantId(), u.apiKeyHash(), u.createdAt(), u.lastLoginAt());
        });
        when(adminAuthService.login(anyString(), anyString(), anyString())).thenAnswer(inv ->
                new AdminAuthService.LoginResult("v1.42.fakeSessionToken",
                        new AdminUser(42L, inv.getArgument(1), "Demo Admin",
                                com.company.agentgateway.domain.iam.admin.AdminRole.OWNER,
                                com.company.agentgateway.domain.iam.admin.AdminStatus.ACTIVE,
                                inv.getArgument(0), "phc", Instant.now(), Instant.now())));

        service = new DemoService(config, apiKeyStore, adminUserRepo, adminAuthService);
    }

    @Test
    void bootstrapEnabledReturnsTenantApiKeyAndAdminToken() {
        DemoSession session = service.bootstrap();

        assertThat(session.tenantId()).startsWith("demo-");
        assertThat(session.apiKey()).startsWith("sk-demo-").hasSizeGreaterThan(20);
        assertThat(session.adminToken()).startsWith("v1.");
        assertThat(session.adminEmail()).endsWith("@demo.local");
        assertThat(session.expiresAt()).isAfter(Instant.now().plus(Duration.ofHours(23)));
    }

    @Test
    void bootstrapRegistersApiKeyWithExpiry() {
        service.bootstrap();

        ArgumentCaptor<ApiKeyStore.ApiKeyBinding> bindingCap = ArgumentCaptor.forClass(ApiKeyStore.ApiKeyBinding.class);
        org.mockito.Mockito.verify(apiKeyStore).register(anyString(), bindingCap.capture());
        ApiKeyStore.ApiKeyBinding binding = bindingCap.getValue();

        assertThat(binding.tenant().value()).startsWith("demo-");
        assertThat(binding.expiresAt()).isAfter(Instant.now().plus(Duration.ofHours(23)));
    }

    @Test
    void bootstrapDisabledThrows() {
        config.setEnabled(false);
        assertThatThrownBy(() -> service.bootstrap())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void cleanupRemovesExpiredDemoKeys() {
        Instant past = Instant.now().minus(Duration.ofHours(1));
        Instant future = Instant.now().plus(Duration.ofHours(23));
        List<java.util.Map.Entry<String, ApiKeyStore.ApiKeyBinding>> entries = new ArrayList<>();
        entries.add(entry("sk-demo-expired", tenant("demo-aaa"), past));
        entries.add(entry("sk-demo-fresh", tenant("demo-bbb"), future));
        when(apiKeyStore.entries()).thenReturn(entries);

        int removed = service.cleanup();

        assertThat(removed).isEqualTo(1);
        org.mockito.Mockito.verify(apiKeyStore).revoke("sk-demo-expired");
        org.mockito.Mockito.verify(apiKeyStore, org.mockito.Mockito.never()).revoke("sk-demo-fresh");
    }

    @Test
    void cleanupIgnoresNonDemoKeys() {
        Instant past = Instant.now().minus(Duration.ofHours(1));
        when(apiKeyStore.entries()).thenReturn(List.of(
                entry("sk-fixed-001", tenant("primary"), past)));

        int removed = service.cleanup();

        assertThat(removed).isZero();
        org.mockito.Mockito.verify(apiKeyStore, org.mockito.Mockito.never()).revoke(anyString());
    }

    @Test
    void cleanupDisabledIsNoop() {
        config.setEnabled(false);
        when(apiKeyStore.entries()).thenReturn(List.of(
                entry("sk-demo-expired", tenant("demo-xxx"), Instant.now().minusSeconds(60))));

        int removed = service.cleanup();
        assertThat(removed).isZero();
    }

    private static java.util.Map.Entry<String, ApiKeyStore.ApiKeyBinding> entry(
            String key, com.company.agentgateway.domain.shared.TenantId tenant, Instant expiresAt) {
        return java.util.Map.entry(key, new ApiKeyStore.ApiKeyBinding(
                tenant,
                new com.company.agentgateway.domain.shared.UserId(tenant.value()),
                java.util.Set.of(),
                java.util.Set.of(),
                false,
                java.util.Set.of(tenant),
                expiresAt));
    }

    private static com.company.agentgateway.domain.shared.TenantId tenant(String value) {
        return new com.company.agentgateway.domain.shared.TenantId(value);
    }
}