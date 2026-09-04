package com.company.agentgateway.application.admin;

import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.iam.admin.AdminUser;
import com.company.agentgateway.domain.iam.admin.Team;
import com.company.agentgateway.infra.persistence.admin.InMemoryAdminUserRepository;
import com.company.agentgateway.infra.persistence.admin.InMemoryTeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamServiceTest {

    private InMemoryAdminUserRepository userRepo;
    private InMemoryTeamRepository teamRepo;
    private AdminUserService userService;
    private TeamService teamService;
    private AdminUser owner;
    private AdminUser admin;
    private AdminUser viewer;

    @BeforeEach
    void setUp() {
        userRepo = new InMemoryAdminUserRepository();
        teamRepo = new InMemoryTeamRepository();
        userService = new AdminUserService(userRepo);
        teamService = new TeamService(teamRepo, userRepo);
        owner = userService.register("o@x.com", "O", AdminRole.OWNER, "au", null, AdminRole.OWNER);
        admin = userService.register("a@x.com", "A", AdminRole.ADMIN, "au", null, AdminRole.OWNER);
        viewer = userService.register("v@x.com", "V", AdminRole.VIEWER, "au", null, AdminRole.OWNER);
    }

    @Test
    void create_adminCaller_succeeds() {
        Team t = teamService.create("platform", "au", owner.id(), AdminRole.ADMIN);
        assertTrue(t.id() > 0);
        assertEquals("platform", t.name());
    }

    @Test
    void create_viewerCaller_rejected() {
        assertThrows(SecurityException.class, () ->
                teamService.create("p", "au", owner.id(), AdminRole.VIEWER));
    }

    @Test
    void create_ownerMustExist() {
        assertThrows(IllegalArgumentException.class, () ->
                teamService.create("p", "au", 99999L, AdminRole.ADMIN));
    }

    @Test
    void create_duplicateNameRejected() {
        teamService.create("platform", "au", owner.id(), AdminRole.ADMIN);
        assertThrows(IllegalStateException.class, () ->
                teamService.create("platform", "au", owner.id(), AdminRole.ADMIN));
    }

    @Test
    void addMember_tenantMismatchRejected() {
        Team t = teamService.create("platform", "au", owner.id(), AdminRole.ADMIN);
        AdminUser cn = userService.register("c@x.com", "C", AdminRole.VIEWER, "cn", null, AdminRole.OWNER);
        assertThrows(IllegalArgumentException.class, () ->
                teamService.addMember(t.id(), cn.id(), AdminRole.ADMIN));
    }

    @Test
    void removeMember_ownerProtected() {
        Team t = teamService.create("platform", "au", owner.id(), AdminRole.ADMIN);
        assertThrows(IllegalArgumentException.class, () ->
                teamService.removeMember(t.id(), owner.id(), AdminRole.ADMIN));
    }

    @Test
    void transferOwnership_onlyOwner() {
        Team t = teamService.create("platform", "au", owner.id(), AdminRole.OWNER);
        assertThrows(SecurityException.class, () ->
                teamService.transferOwnership(t.id(), admin.id(), AdminRole.ADMIN));
    }

    @Test
    void transferOwnership_adminToAdmin() {
        Team t = teamService.create("platform", "au", owner.id(), AdminRole.OWNER);
        teamService.addMember(t.id(), admin.id(), AdminRole.OWNER);
        Team transferred = teamService.transferOwnership(t.id(), admin.id(), AdminRole.OWNER);
        assertEquals(admin.id(), transferred.ownerId());
        // 旧 owner 进入 memberIds
        assertTrue(transferred.memberIds().contains(owner.id()));
        // 新 owner 不再是 member
        assertTrue(!transferred.memberIds().contains(admin.id()));
    }
}
