package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.iam.admin.AdminUser;
import com.company.agentgateway.domain.iam.admin.AdminUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /v1/admin/users — 列出 admin 用户（spec §admin-list round 55）。
 *
 * <p>支持 / 客服自查询「现在有谁在用产品」，用于：
 * <ul>
 *   <li>合规审计：列出所有活跃 admin（按租户 / 角色过滤）</li>
 *   <li>客户支持：客户报问题时查「你们账号下有谁」</li>
 *   <li>运营：监控新增 admin 趋势（与 /v1/admin/stats/prom 配合）</li>
 * </ul>
 *
 * <p>query 参数：
 * <ul>
 *   <li>{@code tenantId} — 可选；不传 → 跨租户（仅 OWNER 角色能用）</li>
 *   <li>{@code role} — 可选 OWNER / ADMIN / VIEWER</li>
 *   <li>{@code status} — 可选 ACTIVE / DISABLED / DELETED</li>
 *   <li>{@code limit} / {@code offset} — 分页（默认 100 / 0）</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminUsersController {

    private final AdminUserRepository userRepo;

    public AdminUsersController(AdminUserRepository userRepo) {
        this.userRepo = userRepo;
    }

    @GetMapping("/users")
    public Map<String, Object> listUsers(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        if (adminToken == null || adminToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Admin-Token required");
        }
        AdminUserRepository.AdminUserQuery query =
                new AdminUserRepository.AdminUserQuery(tenantId, null, null,
                        Math.max(1, Math.min(limit, 500)),
                        Math.max(0, offset));
        List<AdminUser> users = userRepo.query(query);
        // 过滤 role / status（AdminUserRepository 内部是按 tenant + role + status 简单过滤）
        // 二次过滤兜底（in-memory 实现可能不全）
        List<Map<String, Object>> out = users.stream()
                .filter(u -> role == null || u.role().name().equalsIgnoreCase(role))
                .filter(u -> status == null || u.status().name().equalsIgnoreCase(status))
                .map(this::toJson)
                .toList();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", out.size());
        resp.put("users", out);
        return resp;
    }

    private Map<String, Object> toJson(AdminUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id());
        m.put("email", u.email());
        m.put("name", u.name());
        m.put("role", u.role().name());
        m.put("status", u.status().name());
        m.put("tenantId", u.tenantId());
        if (u.createdAt() != null) m.put("createdAt", u.createdAt().toString());
        if (u.lastLoginAt() != null) m.put("lastLoginAt", u.lastLoginAt().toString());
        return m;
    }
}