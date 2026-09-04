package com.company.agentgateway.domain.iam.admin;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamTest {

    @Test
    void create_setsEmptyMembersAndIdZero() {
        Team t = Team.create("platform-team", "au", 42L);
        assertEquals(0L, t.id());
        assertEquals(42L, t.ownerId());
        assertTrue(t.memberIds().isEmpty());
        assertNotNull(t.createdAt());
    }

    @Test
    void rejectsBlankNameAndTenant() {
        assertThrows(IllegalArgumentException.class, () -> Team.create("", "au", 1L));
        assertThrows(IllegalArgumentException.class, () -> Team.create("n", "", 1L));
    }

    @Test
    void rejectsZeroOrNegativeOwnerId() {
        assertThrows(IllegalArgumentException.class, () -> Team.create("n", "au", 0L));
        assertThrows(IllegalArgumentException.class, () -> Team.create("n", "au", -1L));
    }

    @Test
    void ownerIdIsExcludedFromMemberIds() {
        // 即使传入包含 ownerId 的集合,record 构造时会过滤掉
        Team t = new Team(1L, "team", "au", 42L, Set.of(42L, 7L, 8L), null);
        assertFalse(t.memberIds().contains(42L));
        assertTrue(t.memberIds().contains(7L));
        assertTrue(t.memberIds().contains(8L));
    }

    @Test
    void withMembers_returnsNewInstance() {
        Team t = Team.create("team", "au", 42L);
        Team t2 = t.withMembers(Set.of(7L, 8L));
        assertEquals(2, t2.memberIds().size());
        // 原 team 不变
        assertTrue(t.memberIds().isEmpty());
    }

    @Test
    void isMember_includesOwnerAndListed() {
        Team t = Team.create("team", "au", 42L).withMembers(Set.of(7L));
        assertTrue(t.isMember(42L));
        assertTrue(t.isMember(7L));
        assertFalse(t.isMember(99L));
    }

    @Test
    void size_includesOwner() {
        Team t = Team.create("team", "au", 42L).withMembers(Set.of(7L, 8L));
        assertEquals(3, t.size());
    }

    @Test
    void toMap_producesFlatShape() {
        Team t = new Team(5L, "team", "au", 42L, Set.of(7L),
                java.time.Instant.parse("2026-09-01T10:00:00Z"));
        Map<String, Object> m = t.toMap();
        assertEquals(5L, m.get("id"));
        assertEquals("team", m.get("name"));
        assertEquals(42L, m.get("ownerId"));
        assertEquals(2, m.get("size"));
    }
}
