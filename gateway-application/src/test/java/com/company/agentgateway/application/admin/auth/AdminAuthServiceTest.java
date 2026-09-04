package com.company.agentgateway.application.admin.auth;

import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.iam.admin.AdminUser;
import com.company.agentgateway.infra.persistence.admin.InMemoryAdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAuthServiceTest {

    private InMemoryAdminUserRepository userRepo;
    private AdminAuthService auth;
    private AdminUser alice;

    @BeforeEach
    void setUp() {
        userRepo = new InMemoryAdminUserRepository();
        auth = new AdminAuthService(userRepo);
        // 直接注册带密码哈希的 Admin
        AdminUser raw = userRepo.save(AdminUser.create("alice@x.com", "Alice",
                AdminRole.ADMIN, "au", null));
        alice = auth.setPassword(raw.id(), "secret123");
    }

    @Test
    void login_validCredentials_returnsToken() {
        AdminAuthService.LoginResult r = auth.login("au", "alice@x.com", "secret123");
        assertNotNull(r.token());
        assertTrue(r.token().startsWith("v1."));
        assertEquals(AdminRole.ADMIN, r.user().role());
    }

    @Test
    void login_wrongPassword_throws() {
        assertThrows(SecurityException.class,
                () -> auth.login("au", "alice@x.com", "wrong"));
    }

    @Test
    void login_unknownEmail_throws() {
        assertThrows(SecurityException.class,
                () -> auth.login("au", "nobody@x.com", "x"));
    }

    @Test
    void login_suspendedAccount_throws() {
        AdminUser u = userRepo.findByEmail("au", "alice@x.com").get();
        userRepo.save(new AdminUser(u.id(), u.email(), u.name(),
                u.role(), com.company.agentgateway.domain.iam.admin.AdminStatus.SUSPENDED,
                u.tenantId(), u.apiKeyHash(), u.createdAt(), u.lastLoginAt()));
        assertThrows(IllegalStateException.class,
                () -> auth.login("au", "alice@x.com", "secret123"));
    }

    @Test
    void verifyToken_returnsRole() {
        AdminAuthService.LoginResult r = auth.login("au", "alice@x.com", "secret123");
        assertEquals(AdminRole.ADMIN, auth.verifyToken(r.token()).get());
    }

    @Test
    void verifyToken_invalid() {
        assertTrue(auth.verifyToken("v1.fake.token").isEmpty());
        assertTrue(auth.verifyToken(null).isEmpty());
        assertTrue(auth.verifyToken("").isEmpty());
    }

    @Test
    void logout_invalidatesToken() {
        AdminAuthService.LoginResult r = auth.login("au", "alice@x.com", "secret123");
        auth.logout(r.token());
        assertTrue(auth.verifyToken(r.token()).isEmpty());
    }

    @Test
    void setPassword_changesCredential() {
        AdminAuthService.LoginResult r1 = auth.login("au", "alice@x.com", "secret123");
        auth.setPassword(alice.id(), "newpass456");
        // 旧密码失效
        assertThrows(SecurityException.class,
                () -> auth.login("au", "alice@x.com", "secret123"));
        // 新密码 OK
        AdminAuthService.LoginResult r2 = auth.login("au", "alice@x.com", "newpass456");
        assertNotNull(r2.token());
        assertTrue(!r1.token().equals(r2.token()));
    }
}
