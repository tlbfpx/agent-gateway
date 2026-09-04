package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.application.admin.auth.AdminAuthService;
import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.iam.admin.AdminUser;
import com.company.agentgateway.infra.persistence.admin.InMemoryAdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAuthControllerTest {

    private AdminAuthController controller;
    private AdminAuthService auth;
    private long aliceId;

    @BeforeEach
    void setUp() {
        InMemoryAdminUserRepository userRepo = new InMemoryAdminUserRepository();
        auth = new AdminAuthService(userRepo);
        controller = new AdminAuthController(auth);

        AdminUser raw = userRepo.save(AdminUser.create("alice@x.com", "Alice",
                AdminRole.ADMIN, "au", null));
        aliceId = raw.id();
        auth.setPassword(aliceId, "secret123");
    }

    @Test
    void login_validReturnsToken() {
        Map<String, Object> out = controller.login(loginBody("au", "alice@x.com", "secret123"));
        String token = (String) out.get("token");
        assertNotNull(token);
        assertTrue(token.startsWith("v1."));
    }

    @Test
    void login_wrongPassword_throws401() {
        Map<String, Object> body = loginBody("au", "alice@x.com", "wrong");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.login(body));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void login_missingEmail_returns400() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", "au");
        body.put("password", "x");
        assertThrows(ResponseStatusException.class, () -> controller.login(body));
    }

    @Test
    void me_validToken_returnsRole() {
        Map<String, Object> loginOut = controller.login(loginBody("au", "alice@x.com", "secret123"));
        String token = (String) loginOut.get("token");
        Map<String, Object> me = controller.me(token);
        assertEquals("ADMIN", me.get("role"));
    }

    @Test
    void me_invalidToken_throws401() {
        assertThrows(ResponseStatusException.class, () -> controller.me("v1.fake.token"));
        assertThrows(ResponseStatusException.class, () -> controller.me(null));
    }

    @Test
    void logout_invalidates() {
        Map<String, Object> loginOut = controller.login(loginBody("au", "alice@x.com", "secret123"));
        String token = (String) loginOut.get("token");
        controller.logout(token);
        assertThrows(ResponseStatusException.class, () -> controller.me(token));
    }

    @Test
    void changePassword_shortPassword_rejected() {
        Map<String, Object> loginOut = controller.login(loginBody("au", "alice@x.com", "secret123"));
        String token = (String) loginOut.get("token");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("adminId", aliceId);
        body.put("newPassword", "short");
        assertThrows(ResponseStatusException.class, () -> controller.changePassword(token, body));
    }

    @Test
    void changePassword_succeeds() {
        Map<String, Object> loginOut = controller.login(loginBody("au", "alice@x.com", "secret123"));
        String token = (String) loginOut.get("token");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("adminId", aliceId);
        body.put("newPassword", "newsecret456");
        Map<String, Object> out = controller.changePassword(token, body);
        assertEquals("alice@x.com", out.get("email"));

        // 旧密码失效
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.login(loginBody("au", "alice@x.com", "secret123")));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void changePassword_invalidToken_throws401() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("adminId", aliceId);
        body.put("newPassword", "newsecret456");
        assertThrows(ResponseStatusException.class, () -> controller.changePassword("v1.fake.token", body));
    }

    private static Map<String, Object> loginBody(String tenant, String email, String pw) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", tenant);
        m.put("email", email);
        m.put("password", pw);
        return m;
    }
}
