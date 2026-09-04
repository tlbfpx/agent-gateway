package com.company.agentgateway.domain.iam.admin;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminUserTest {

    @Test
    void create_setsIdZeroAndNowCreatedAt() {
        AdminUser u = AdminUser.create("alice@example.com", "Alice",
                AdminRole.ADMIN, "au", null);
        assertEquals(0L, u.id());
        assertEquals(AdminStatus.ACTIVE, u.status());
        assertNotNull(u.createdAt());
    }

    @Test
    void rejectsInvalidEmail() {
        assertThrows(IllegalArgumentException.class, () -> AdminUser.create(
                "no-at-sign", "x", AdminRole.VIEWER, "au", null));
        assertThrows(IllegalArgumentException.class, () -> AdminUser.create(
                "", "x", AdminRole.VIEWER, "au", null));
        assertThrows(IllegalArgumentException.class, () -> AdminUser.create(
                null, "x", AdminRole.VIEWER, "au", null));
    }

    @Test
    void rejectsBlankNameAndTenant() {
        assertThrows(IllegalArgumentException.class, () -> AdminUser.create(
                "a@b.com", "", AdminRole.VIEWER, "au", null));
        assertThrows(IllegalArgumentException.class, () -> AdminUser.create(
                "a@b.com", "x", AdminRole.VIEWER, "", null));
    }

    @Test
    void rejectsNullRole() {
        assertThrows(IllegalArgumentException.class, () -> AdminUser.create(
                "a@b.com", "x", null, "au", null));
    }

    @Test
    void toMap_producesFlatShape() {
        AdminUser u = new AdminUser(
                7L, "a@b.com", "Alice", AdminRole.OWNER, AdminStatus.ACTIVE,
                "au", null,
                Instant.parse("2026-09-01T10:00:00Z"),
                Instant.parse("2026-09-02T08:00:00Z"));
        Map<String, Object> m = u.toMap();
        assertEquals(7L, m.get("id"));
        assertEquals("a@b.com", m.get("email"));
        assertEquals("OWNER", m.get("role"));
        assertEquals("ACTIVE", m.get("status"));
        assertEquals("2026-09-02T08:00:00Z", m.get("lastLoginAt"));
    }
}

class AdminRoleTest {
    @Test
    void atLeast_isMonotonic() {
        assertTrue(AdminRole.OWNER.atLeast(AdminRole.ADMIN));
        assertTrue(AdminRole.OWNER.atLeast(AdminRole.VIEWER));
        assertTrue(AdminRole.ADMIN.atLeast(AdminRole.OPERATOR));
        assertFalse(AdminRole.VIEWER.atLeast(AdminRole.ADMIN));
        assertTrue(AdminRole.OWNER.atLeast(AdminRole.OWNER));
    }

    @Test
    void parse_caseInsensitive() {
        assertEquals(AdminRole.OWNER, AdminRole.parse("owner"));
        assertEquals(AdminRole.ADMIN, AdminRole.parse("Admin"));
        assertEquals(AdminRole.OPERATOR, AdminRole.parse("OPERATOR"));
    }

    @Test
    void parse_rejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> AdminRole.parse("god"));
        assertThrows(IllegalArgumentException.class, () -> AdminRole.parse(""));
        assertThrows(IllegalArgumentException.class, () -> AdminRole.parse(null));
    }
}

class AdminStatusTest {
    @Test
    void canLogin_activeOnly() {
        assertTrue(AdminStatus.ACTIVE.canLogin());
        assertFalse(AdminStatus.SUSPENDED.canLogin());
        assertFalse(AdminStatus.DELETED.canLogin());
    }

    @Test
    void parse_caseInsensitive() {
        assertEquals(AdminStatus.ACTIVE, AdminStatus.parse("active"));
        assertEquals(AdminStatus.SUSPENDED, AdminStatus.parse("Suspended"));
    }
}
