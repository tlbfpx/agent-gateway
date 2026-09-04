package com.company.agentgateway.interfaces.auth;

import com.company.agentgateway.application.admin.auth.AdminAuthService;
import com.company.agentgateway.domain.iam.admin.AdminUser;
import com.company.agentgateway.domain.iam.admin.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SignupService 单元测试（spec 2026-09-04 §self-serve-signup §7）。
 */
class SignupServiceTest {

    private AdminUserRepository adminUserRepo;
    private AdminAuthService adminAuthService;
    private SignupService service;

    @BeforeEach
    void setUp() {
        adminUserRepo = mock(AdminUserRepository.class);
        adminAuthService = mock(AdminAuthService.class);
        when(adminUserRepo.findByEmail(anyString(), anyString())).thenReturn(Optional.empty());
        when(adminUserRepo.save(any(AdminUser.class))).thenAnswer(inv -> {
            AdminUser u = inv.getArgument(0);
            return new AdminUser(1L, u.email(), u.name(), u.role(), u.status(),
                    u.tenantId(), u.apiKeyHash(), u.createdAt(), u.lastLoginAt());
        });
        when(adminAuthService.login(anyString(), anyString(), anyString())).thenAnswer(inv ->
                new AdminAuthService.LoginResult("v1.1.sessionToken",
                        new AdminUser(1L, inv.getArgument(1), "Acme",
                                com.company.agentgateway.domain.iam.admin.AdminRole.OWNER,
                                com.company.agentgateway.domain.iam.admin.AdminStatus.ACTIVE,
                                inv.getArgument(0), "phc", Instant.now(), Instant.now())));
        service = new SignupService(adminUserRepo, adminAuthService);
    }

    @Test
    void signupReturnsTenantEmailAndSessionToken() {
        SignupResult r = service.signup("alice@acme.io", "password123", "Acme Co");
        assertThat(r.tenantId()).startsWith("acme-co-").hasSizeGreaterThan(8);
        assertThat(r.email()).isEqualTo("alice@acme.io");
        assertThat(r.adminToken()).startsWith("v1.");
    }

    @Test
    void signupDerivesTenantIdFromCompanyName() {
        assertThat(SignupService.deriveTenantId("Acme Co."))
                .startsWith("acme-co-").doesNotContain(".");
        assertThat(SignupService.deriveTenantId("Hello World!!"))
                .startsWith("hello-world-").doesNotContain("!");
        assertThat(SignupService.deriveTenantId("中文"))
                .startsWith("tenant-");
    }

    @Test
    void signupRejectsInvalidEmail() {
        assertThatThrownBy(() -> service.signup("not-an-email", "password123", "Acme"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
    }

    @Test
    void signupRejectsShortPassword() {
        assertThatThrownBy(() -> service.signup("alice@acme.io", "short", "Acme"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");
    }

    @Test
    void signupRejectsBlankCompanyName() {
        assertThatThrownBy(() -> service.signup("alice@acme.io", "password123", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("companyName");
    }

    @Test
    void signupReturns409WhenEmailExists() {
        when(adminUserRepo.findByEmail(anyString(), anyString()))
                .thenReturn(Optional.of(mock(AdminUser.class)));
        assertThatThrownBy(() -> service.signup("alice@acme.io", "password123", "Acme"))
                .isInstanceOf(SignupService.EmailAlreadyExistsException.class);
    }

    @Test
    void signupPersistsAdminUserAsOwner() {
        service.signup("Alice@Acme.IO", "password123", "Acme Co");

        ArgumentCaptor<AdminUser> cap = ArgumentCaptor.forClass(AdminUser.class);
        org.mockito.Mockito.verify(adminUserRepo).save(cap.capture());
        AdminUser saved = cap.getValue();

        assertThat(saved.email()).isEqualTo("alice@acme.io"); // 小写归一
        assertThat(saved.role())
                .isEqualTo(com.company.agentgateway.domain.iam.admin.AdminRole.OWNER);
        assertThat(saved.tenantId()).startsWith("acme-co-");
        assertThat(saved.apiKeyHash()).startsWith("$pbkdf2-sha256$"); // bcrypt 输出格式
    }
}