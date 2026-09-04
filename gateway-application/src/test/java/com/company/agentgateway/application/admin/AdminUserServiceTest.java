package com.company.agentgateway.application.admin;

import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.iam.admin.AdminStatus;
import com.company.agentgateway.domain.iam.admin.AdminUser;
import com.company.agentgateway.infra.persistence.admin.InMemoryAdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminUserServiceTest {

    private InMemoryAdminUserRepository repo;
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        repo = new InMemoryAdminUserRepository();
        service = new AdminUserService(repo);
    }

    @Test
    void register_adminCaller_canCreateViewer() {
        AdminUser u = service.register("alice@x.com", "Alice", AdminRole.VIEWER, "au", null, AdminRole.ADMIN);
        assertNotNull(u.id());
        assertEquals(AdminRole.VIEWER, u.role());
        assertEquals(AdminStatus.ACTIVE, u.status());
    }

    @Test
    void register_operatorCaller_rejected() {
        assertThrows(SecurityException.class, () ->
                service.register("a@x.com", "A", AdminRole.VIEWER, "au", null, AdminRole.OPERATOR));
    }

    @Test
    void register_nonOwnerCannotCreateOwner() {
        assertThrows(SecurityException.class, () ->
                service.register("a@x.com", "A", AdminRole.OWNER, "au", null, AdminRole.ADMIN));
    }

    @Test
    void register_duplicateEmailRejected() {
        service.register("a@x.com", "A", AdminRole.VIEWER, "au", null, AdminRole.ADMIN);
        assertThrows(IllegalStateException.class, () ->
                service.register("a@x.com", "A2", AdminRole.VIEWER, "au", null, AdminRole.ADMIN));
    }

    @Test
    void changeRole_ownerCaller_canPromoteToOwner() {
        AdminUser u = service.register("a@x.com", "A", AdminRole.ADMIN, "au", null, AdminRole.OWNER);
        AdminUser updated = service.changeRole(u.id(), AdminRole.OWNER, AdminRole.OWNER);
        assertEquals(AdminRole.OWNER, updated.role());
    }

    @Test
    void changeRole_adminCaller_cannotTouchOwner() {
        AdminUser owner = service.register("o@x.com", "O", AdminRole.OWNER, "au", null, AdminRole.OWNER);
        assertThrows(SecurityException.class, () ->
                service.changeRole(owner.id(), AdminRole.VIEWER, AdminRole.ADMIN));
    }

    @Test
    void suspend_adminCanSuspendViewer() {
        AdminUser u = service.register("a@x.com", "A", AdminRole.VIEWER, "au", null, AdminRole.ADMIN);
        AdminUser suspended = service.suspend(u.id(), AdminRole.ADMIN);
        assertEquals(AdminStatus.SUSPENDED, suspended.status());
    }

    @Test
    void delete_ownerOnly_cannotDeleteOwner() {
        AdminUser owner = service.register("o@x.com", "O", AdminRole.OWNER, "au", null, AdminRole.OWNER);
        assertThrows(SecurityException.class, () -> service.delete(owner.id(), AdminRole.OWNER));
    }

    @Test
    void delete_ownerCanDeleteAdmin() {
        AdminUser admin = service.register("a@x.com", "A", AdminRole.ADMIN, "au", null, AdminRole.OWNER);
        assertTrue(service.delete(admin.id(), AdminRole.OWNER));
    }

    @Test
    void recordLogin_setsLastLoginAt() throws InterruptedException {
        AdminUser u = service.register("a@x.com", "A", AdminRole.VIEWER, "au", null, AdminRole.ADMIN);
        assertEquals(null, u.lastLoginAt());
        AdminUser loggedIn = service.recordLogin(u.id());
        assertNotNull(loggedIn.lastLoginAt());
    }
}
