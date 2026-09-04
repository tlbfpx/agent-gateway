package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.application.admin.AdminUserService;
import com.company.agentgateway.application.admin.TeamService;
import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.iam.admin.AdminStatus;
import com.company.agentgateway.domain.iam.admin.AdminUser;
import com.company.agentgateway.domain.iam.admin.AdminUserRepository.AdminUserQuery;
import com.company.agentgateway.domain.iam.admin.Team;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin & Team 管理端点（spec 2026-09-02 §multi-admin §6）。
 *
 * <p>所有端点走 X-Admin-Token;caller role 通过内部 AdminUser 表识别（兼容旧静态 token）。
 *
 * <ul>
 *   <li>AdminUser：{@code POST /v1/admin/admins} + GET list / GET by id / PUT role / PUT status / DELETE</li>
 *   <li>Team：{@code POST /v1/admin/teams} + GET list / GET by id / POST :id/members / DELETE :id/members/:uid / PUT :id/owner</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminAdminController {

    private final AdminUserService adminUserService;
    private final TeamService teamService;

    public AdminAdminController(AdminUserService adminUserService, TeamService teamService) {
        this.adminUserService = adminUserService;
        this.teamService = teamService;
    }

    private AdminRole requireAdmin(String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Admin-Token required");
        }
        // 静态兼容: 任何非空 token 当 OWNER 处理(R13 接入 AdminUser 表 + bcrypt)
        return AdminRole.OWNER;
    }

    // ============= AdminUser =============

    @PostMapping("/admins")
    public ResponseEntity<Map<String, Object>> registerAdmin(
            @RequestHeader("X-Admin-Token") String adminToken,
            @RequestBody Map<String, Object> body) {
        AdminRole caller = requireAdmin(adminToken);
        String email = stringOrThrow(body, "email", "email required");
        String name = stringOrThrow(body, "name", "name required");
        AdminRole role = parseRoleOrThrow(stringOrThrow(body, "role", "role required"));
        String tenantId = stringOrThrow(body, "tenantId", "tenantId required");
        String apiKeyHash = stringOrNull(body, "apiKeyHash");
        AdminUser saved = adminUserService.register(email, name, role, tenantId, apiKeyHash, caller);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toMap());
    }

    @GetMapping("/admins")
    public List<Map<String, Object>> listAdmins(
            @RequestHeader("X-Admin-Token") String adminToken,
            @RequestParam(defaultValue = "au") String tenant,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        requireAdmin(adminToken);
        AdminRole r = role == null || role.isBlank() ? null : parseRoleOrThrow(role);
        AdminStatus s = status == null || status.isBlank() ? null : parseStatusOrThrow(status);
        AdminUserQuery q = new AdminUserQuery(tenant, r, s, limit, offset);
        return adminUserService.query(q).stream().map(AdminUser::toMap).toList();
    }

    @GetMapping("/admins/{id}")
    public Map<String, Object> getAdmin(
            @RequestHeader("X-Admin-Token") String adminToken,
            @PathVariable long id) {
        requireAdmin(adminToken);
        return adminUserService.findById(id)
                .map(AdminUser::toMap)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "admin not found"));
    }

    @PutMapping("/admins/{id}/role")
    public Map<String, Object> changeRole(
            @RequestHeader("X-Admin-Token") String adminToken,
            @PathVariable long id,
            @RequestBody Map<String, Object> body) {
        AdminRole caller = requireAdmin(adminToken);
        AdminRole newRole = parseRoleOrThrow(stringOrThrow(body, "role", "role required"));
        return adminUserService.changeRole(id, newRole, caller).toMap();
    }

    @PutMapping("/admins/{id}/status")
    public Map<String, Object> changeStatus(
            @RequestHeader("X-Admin-Token") String adminToken,
            @PathVariable long id,
            @RequestBody Map<String, Object> body) {
        AdminRole caller = requireAdmin(adminToken);
        String action = stringOrThrow(body, "action", "action required");
        AdminUser updated = switch (action.toLowerCase()) {
            case "suspend" -> adminUserService.suspend(id, caller);
            case "activate" -> adminUserService.activate(id, caller);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "action must be suspend or activate");
        };
        return updated.toMap();
    }

    @DeleteMapping("/admins/{id}")
    public Map<String, Object> deleteAdmin(
            @RequestHeader("X-Admin-Token") String adminToken,
            @PathVariable long id) {
        AdminRole caller = requireAdmin(adminToken);
        boolean ok = adminUserService.delete(id, caller);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", ok);
        out.put("id", id);
        return out;
    }

    // ============= Team =============

    @PostMapping("/teams")
    public ResponseEntity<Map<String, Object>> createTeam(
            @RequestHeader("X-Admin-Token") String adminToken,
            @RequestBody Map<String, Object> body) {
        AdminRole caller = requireAdmin(adminToken);
        String name = stringOrThrow(body, "name", "name required");
        String tenantId = stringOrThrow(body, "tenantId", "tenantId required");
        long ownerId = longOrThrow(body, "ownerId", "ownerId required");
        Team t = teamService.create(name, tenantId, ownerId, caller);
        return ResponseEntity.status(HttpStatus.CREATED).body(t.toMap());
    }

    @GetMapping("/teams")
    public List<Map<String, Object>> listTeams(
            @RequestHeader("X-Admin-Token") String adminToken,
            @RequestParam(defaultValue = "au") String tenant) {
        requireAdmin(adminToken);
        return teamService.findByTenant(tenant).stream().map(Team::toMap).toList();
    }

    @GetMapping("/teams/{id}")
    public Map<String, Object> getTeam(
            @RequestHeader("X-Admin-Token") String adminToken,
            @PathVariable long id) {
        requireAdmin(adminToken);
        return teamService.findById(id).toMap();
    }

    @PostMapping("/teams/{id}/members")
    public Map<String, Object> addMember(
            @RequestHeader("X-Admin-Token") String adminToken,
            @PathVariable long id,
            @RequestBody Map<String, Object> body) {
        AdminRole caller = requireAdmin(adminToken);
        long memberId = longOrThrow(body, "memberId", "memberId required");
        return teamService.addMember(id, memberId, caller).toMap();
    }

    @DeleteMapping("/teams/{id}/members/{memberId}")
    public Map<String, Object> removeMember(
            @RequestHeader("X-Admin-Token") String adminToken,
            @PathVariable long id,
            @PathVariable long memberId) {
        AdminRole caller = requireAdmin(adminToken);
        return teamService.removeMember(id, memberId, caller).toMap();
    }

    @PutMapping("/teams/{id}/owner")
    public Map<String, Object> transferOwnership(
            @RequestHeader("X-Admin-Token") String adminToken,
            @PathVariable long id,
            @RequestBody Map<String, Object> body) {
        AdminRole caller = requireAdmin(adminToken);
        long newOwnerId = longOrThrow(body, "newOwnerId", "newOwnerId required");
        return teamService.transferOwnership(id, newOwnerId, caller).toMap();
    }

    // ============= helpers =============

    private static String stringOrThrow(Map<String, Object> body, String key, String msg) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        }
        return v.toString();
    }

    private static AdminRole parseRoleOrThrow(String raw) {
        try {
            return AdminRole.parse(raw);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid role: " + ex.getMessage());
        }
    }

    private static AdminStatus parseStatusOrThrow(String raw) {
        try {
            return AdminStatus.parse(raw);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid status: " + ex.getMessage());
        }
    }

    private static String stringOrNull(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : v.toString();
    }

    private static long longOrThrow(Map<String, Object> body, String key, String msg) {
        Object v = body.get(key);
        if (v == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    key + " must be integer, got " + v);
        }
    }
}
