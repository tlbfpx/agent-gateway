package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.application.admin.AdminUserService;
import com.company.agentgateway.application.admin.TeamService;
import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.iam.admin.AdminStatus;
import com.company.agentgateway.domain.iam.admin.AdminUser;
import com.company.agentgateway.domain.iam.admin.Team;
import com.company.agentgateway.infra.persistence.admin.InMemoryAdminUserRepository;
import com.company.agentgateway.infra.persistence.admin.InMemoryTeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAdminControllerTest {

    private AdminAdminController controller;

    @BeforeEach
    void setUp() {
        InMemoryAdminUserRepository userRepo = new InMemoryAdminUserRepository();
        InMemoryTeamRepository teamRepo = new InMemoryTeamRepository();
        AdminUserService userService = new AdminUserService(userRepo);
        TeamService teamService = new TeamService(teamRepo, userRepo);
        controller = new AdminAdminController(userService, teamService);
    }

    @Test
    void registerAdmin_returns201WithId() {
        var resp = controller.registerAdmin("token", adminBody("a@x.com", "Alice", "ADMIN", "au"));
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertNotNull(resp.getBody().get("id"));
        assertEquals("ADMIN", resp.getBody().get("role"));
    }

    @Test
    void registerAdmin_rejectsBadRole() {
        Map<String, Object> body = adminBody("a@x.com", "A", "god", "au");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.registerAdmin("token", body));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void listAdmins_returnsList() {
        controller.registerAdmin("token", adminBody("a@x.com", "A", "ADMIN", "au"));
        controller.registerAdmin("token", adminBody("b@x.com", "B", "VIEWER", "au"));
        List<Map<String, Object>> got = controller.listAdmins("token", "au", null, null, 50, 0);
        assertEquals(2, got.size());
    }

    @Test
    void changeRole_succeeds() {
        var resp = controller.registerAdmin("token", adminBody("a@x.com", "A", "VIEWER", "au"));
        long id = ((Number) resp.getBody().get("id")).longValue();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("role", "ADMIN");
        Map<String, Object> out = controller.changeRole("token", id, body);
        assertEquals("ADMIN", out.get("role"));
    }

    @Test
    void changeStatus_suspendActivates() {
        var resp = controller.registerAdmin("token", adminBody("a@x.com", "A", "VIEWER", "au"));
        long id = ((Number) resp.getBody().get("id")).longValue();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "suspend");
        Map<String, Object> suspended = controller.changeStatus("token", id, body);
        assertEquals("SUSPENDED", suspended.get("status"));

        body.put("action", "activate");
        Map<String, Object> activated = controller.changeStatus("token", id, body);
        assertEquals("ACTIVE", activated.get("status"));
    }

    @Test
    void changeStatus_rejectsUnknownAction() {
        var resp = controller.registerAdmin("token", adminBody("a@x.com", "A", "VIEWER", "au"));
        long id = ((Number) resp.getBody().get("id")).longValue();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "freeze");
        assertThrows(ResponseStatusException.class, () -> controller.changeStatus("token", id, body));
    }

    @Test
    void deleteAdmin_softDeletes() {
        var resp = controller.registerAdmin("token", adminBody("a@x.com", "A", "VIEWER", "au"));
        long id = ((Number) resp.getBody().get("id")).longValue();
        Map<String, Object> out = controller.deleteAdmin("token", id);
        assertEquals(true, out.get("deleted"));
        // listAdmins 默认排除 DELETED
        List<Map<String, Object>> after = controller.listAdmins("token", "au", null, null, 50, 0);
        assertTrue(after.stream().noneMatch(a -> ((Number) a.get("id")).longValue() == id));
    }

    @Test
    void createTeam_returns201() {
        var userResp = controller.registerAdmin("token", adminBody("o@x.com", "O", "OWNER", "au"));
        long ownerId = ((Number) userResp.getBody().get("id")).longValue();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "platform");
        body.put("tenantId", "au");
        body.put("ownerId", ownerId);
        var resp = controller.createTeam("token", body);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertEquals("platform", resp.getBody().get("name"));
    }

    @Test
    void teamMemberFlow_addRemove() {
        var ownerResp = controller.registerAdmin("token", adminBody("o@x.com", "O", "OWNER", "au"));
        var memberResp = controller.registerAdmin("token", adminBody("m@x.com", "M", "ADMIN", "au"));
        long ownerId = ((Number) ownerResp.getBody().get("id")).longValue();
        long memberId = ((Number) memberResp.getBody().get("id")).longValue();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "platform");
        body.put("tenantId", "au");
        body.put("ownerId", ownerId);
        var teamResp = controller.createTeam("token", body);
        long teamId = ((Number) teamResp.getBody().get("id")).longValue();

        Map<String, Object> addBody = new LinkedHashMap<>();
        addBody.put("memberId", memberId);
        Map<String, Object> after = controller.addMember("token", teamId, addBody);
        List<Number> members = ((List<?>) after.get("memberIds")).stream().map(n -> (Number) n).toList();
        assertTrue(members.contains(memberId));

        Map<String, Object> afterRemove = controller.removeMember("token", teamId, memberId);
        List<Number> membersAfter = ((List<?>) afterRemove.get("memberIds")).stream().map(n -> (Number) n).toList();
        assertTrue(!membersAfter.contains(memberId));
    }

    @Test
    void transferOwnership_changesOwner() {
        var ownerResp = controller.registerAdmin("token", adminBody("o@x.com", "O", "OWNER", "au"));
        var newOwnerResp = controller.registerAdmin("token", adminBody("n@x.com", "N", "ADMIN", "au"));
        long ownerId = ((Number) ownerResp.getBody().get("id")).longValue();
        long newOwnerId = ((Number) newOwnerResp.getBody().get("id")).longValue();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "platform");
        body.put("tenantId", "au");
        body.put("ownerId", ownerId);
        long teamId = ((Number) controller.createTeam("token", body).getBody().get("id")).longValue();

        Map<String, Object> xferBody = new LinkedHashMap<>();
        xferBody.put("newOwnerId", newOwnerId);
        Map<String, Object> out = controller.transferOwnership("token", teamId, xferBody);
        assertEquals(newOwnerId, ((Number) out.get("ownerId")).longValue());
    }

    @Test
    void registerAdmin_rejectsMissingToken() {
        assertThrows(ResponseStatusException.class,
                () -> controller.registerAdmin(null, adminBody("a@x.com", "A", "VIEWER", "au")));
        assertThrows(ResponseStatusException.class,
                () -> controller.registerAdmin("", adminBody("a@x.com", "A", "VIEWER", "au")));
    }

    private static Map<String, Object> adminBody(String email, String name, String role, String tenant) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", email);
        m.put("name", name);
        m.put("role", role);
        m.put("tenantId", tenant);
        return m;
    }
}
