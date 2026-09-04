package com.company.agentgateway.infra.persistence.admin;

import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.iam.admin.AdminStatus;
import com.company.agentgateway.domain.iam.admin.AdminUser;
import com.company.agentgateway.domain.iam.admin.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryAdminRepositoriesTest {

    private InMemoryAdminUserRepository userRepo;
    private InMemoryTeamRepository teamRepo;

    @BeforeEach
    void setUp() {
        userRepo = new InMemoryAdminUserRepository();
        teamRepo = new InMemoryTeamRepository();
    }

    // ============= AdminUser =============

    @Test
    void save_assignsIncrementingIds() {
        AdminUser a = userRepo.save(AdminUser.create("a@x.com", "A", AdminRole.ADMIN, "au", null));
        AdminUser b = userRepo.save(AdminUser.create("b@x.com", "B", AdminRole.VIEWER, "au", null));
        assertEquals(1L, a.id());
        assertEquals(2L, b.id());
        assertEquals("A", userRepo.findById(1L).get().name());
    }

    @Test
    void findByEmail_caseInsensitiveAndScoped() {
        userRepo.save(AdminUser.create("alice@x.com", "A", AdminRole.ADMIN, "au", null));
        userRepo.save(AdminUser.create("alice@x.com", "A", AdminRole.ADMIN, "cn", null));

        assertTrue(userRepo.findByEmail("au", "Alice@X.COM").isPresent());
        assertTrue(userRepo.findByEmail("cn", "alice@x.com").isPresent());
        assertFalse(userRepo.findByEmail("us", "alice@x.com").isPresent());
    }

    @Test
    void findByRole_filtersCorrectly() {
        userRepo.save(AdminUser.create("a@x.com", "A", AdminRole.OWNER, "au", null));
        userRepo.save(AdminUser.create("b@x.com", "B", AdminRole.ADMIN, "au", null));
        userRepo.save(AdminUser.create("c@x.com", "C", AdminRole.ADMIN, "au", null));

        List<AdminUser> admins = userRepo.findByRole("au", AdminRole.ADMIN);
        assertEquals(2, admins.size());
        assertTrue(admins.stream().allMatch(u -> u.role() == AdminRole.ADMIN));
    }

    @Test
    void delete_marksAsDeletedNotRemoves() {
        AdminUser a = userRepo.save(AdminUser.create("a@x.com", "A", AdminRole.VIEWER, "au", null));
        assertTrue(userRepo.delete(a.id()));
        // findByTenant 默认排除 DELETED
        assertTrue(userRepo.findByTenant("au").stream().noneMatch(u -> u.id() == a.id()));
        // 但 findById 仍能找到
        assertEquals(AdminStatus.DELETED, userRepo.findById(a.id()).get().status());
    }

    @Test
    void query_filtersByStatusAndRole() {
        userRepo.save(AdminUser.create("a@x.com", "A", AdminRole.ADMIN, "au", null));
        AdminUser s = userRepo.save(AdminUser.create("b@x.com", "B", AdminRole.ADMIN, "au", null));
        userRepo.save(AdminUser.create("c@x.com", "C", AdminRole.VIEWER, "au", null));
        userRepo.delete(s.id());

        var qs = new com.company.agentgateway.domain.iam.admin.AdminUserRepository.AdminUserQuery(
                "au", AdminRole.ADMIN, AdminStatus.ACTIVE, 50, 0);
        List<AdminUser> got = userRepo.query(qs);
        assertEquals(1, got.size());
        assertEquals("a@x.com", got.get(0).email());
    }

    // ============= Team =============

    @Test
    void team_save_assignsId() {
        Team t = teamRepo.save(Team.create("platform", "au", 1L));
        assertTrue(t.id() > 0);
        assertEquals("platform", teamRepo.findById(t.id()).get().name());
    }

    @Test
    void team_findByOwner_returnsOwnedTeams() {
        AdminUser owner = userRepo.save(AdminUser.create("o@x.com", "O", AdminRole.OWNER, "au", null));
        AdminUser other = userRepo.save(AdminUser.create("ot@x.com", "OT", AdminRole.OWNER, "au", null));
        teamRepo.save(Team.create("t1", "au", owner.id()));
        teamRepo.save(Team.create("t2", "au", owner.id()));
        teamRepo.save(Team.create("t3", "au", other.id()));

        List<Team> ownerTeams = teamRepo.findByOwner(owner.id());
        assertEquals(2, ownerTeams.size());
    }

    @Test
    void team_findByMember_includesOwnerAndMembers() {
        AdminUser owner = userRepo.save(AdminUser.create("o@x.com", "O", AdminRole.OWNER, "au", null));
        AdminUser member = userRepo.save(AdminUser.create("m@x.com", "M", AdminRole.ADMIN, "au", null));
        Team t = teamRepo.save(Team.create("t", "au", owner.id()));
        teamRepo.addMember(t, member.id());

        assertEquals(1, teamRepo.findByMember(owner.id()).size());
        assertEquals(1, teamRepo.findByMember(member.id()).size());
    }

    @Test
    void team_addMember_persists() {
        AdminUser owner = userRepo.save(AdminUser.create("o@x.com", "O", AdminRole.OWNER, "au", null));
        AdminUser m = userRepo.save(AdminUser.create("m@x.com", "M", AdminRole.ADMIN, "au", null));
        Team t = teamRepo.save(Team.create("t", "au", owner.id()));
        Team updated = teamRepo.addMember(t, m.id());

        assertTrue(updated.memberIds().contains(m.id()));
        Team reread = teamRepo.findById(t.id()).get();
        assertTrue(reread.memberIds().contains(m.id()));
    }

    @Test
    void team_removeMember_ownerProtected() {
        AdminUser owner = userRepo.save(AdminUser.create("o@x.com", "O", AdminRole.OWNER, "au", null));
        Team t = teamRepo.save(Team.create("t", "au", owner.id()));
        try {
            teamRepo.removeMember(t, owner.id());
            assert false : "expected IllegalArgumentException";
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("owner"));
        }
    }

    @Test
    void team_findByName_uniqueScoped() {
        teamRepo.save(Team.create("t", "au", 1L));
        assertTrue(teamRepo.findByName("au", "t").isPresent());
        assertTrue(teamRepo.findByName("cn", "t").isEmpty());
    }

    @Test
    void team_delete_removes() {
        Team t = teamRepo.save(Team.create("t", "au", 1L));
        assertNotNull(teamRepo.findById(t.id()).orElse(null));
        assertTrue(teamRepo.delete(t.id()));
        assertTrue(teamRepo.findById(t.id()).isEmpty());
    }
}
