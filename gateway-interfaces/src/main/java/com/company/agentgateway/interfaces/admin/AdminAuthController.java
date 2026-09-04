package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.application.admin.auth.AdminAuthService;
import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.iam.admin.AdminUser;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin 鉴权端点（spec 2026-09-02 §bcrypt-auth §5）。
 *
 * <ul>
 *   <li>{@code POST /v1/admin/auth/login} —— 用 email + password 换取 token</li>
 *   <li>{@code POST /v1/admin/auth/logout} —— 注销当前 token</li>
 *   <li>{@code POST /v1/admin/auth/me} —— 当前 token 对应的 Admin 信息</li>
 *   <li>{@code POST /v1/admin/auth/change-password} —— 修改当前 Admin 密码</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin/auth")
public class AdminAuthController {

    private final AdminAuthService authService;

    public AdminAuthController(AdminAuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        String tenantId = stringOrThrow(body, "tenantId", "tenantId required");
        String email = stringOrThrow(body, "email", "email required");
        String password = stringOrThrow(body, "password", "password required");
        try {
            AdminAuthService.LoginResult r = authService.login(tenantId, email, password);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("token", r.token());
            out.put("user", r.user().toMap());
            return out;
        } catch (SecurityException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage());
        }
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader("X-Admin-Token") String token) {
        authService.logout(token);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("loggedOut", true);
        return out;
    }

    @PostMapping("/me")
    public Map<String, Object> me(@RequestHeader("X-Admin-Token") String token) {
        AdminRole role = authService.verifyToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "invalid or expired token"));
        // P0:仅返回 role(R15 接 AdminUser 表查 adminId → user)
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", role.name());
        return out;
    }

    @PostMapping("/change-password")
    public Map<String, Object> changePassword(
            @RequestHeader("X-Admin-Token") String token,
            @RequestBody Map<String, Object> body) {
        // P0: 通过 token 反查 adminId(R15 加 userRepo.findByApiKeyHash 反查)
        // 这里先要求 body 带 adminId 字段(简化版)
        long adminId = longOrThrow(body, "adminId", "adminId required");
        String newPassword = stringOrThrow(body, "newPassword", "newPassword required");
        if (newPassword.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "newPassword must be ≥ 8 characters");
        }
        // 仅 OWNER / ADMIN 可改他人密码
        AdminRole role = authService.verifyToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "invalid token"));
        if (!role.atLeast(AdminRole.ADMIN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "need ADMIN role to change password");
        }
        AdminUser u = authService.setPassword(adminId, newPassword);
        return u.toMap();
    }

    private static String stringOrThrow(Map<String, Object> body, String key, String msg) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        }
        return v.toString();
    }

    private static long longOrThrow(Map<String, Object> body, String key, String msg) {
        Object v = body.get(key);
        if (v == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); }
        catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be integer");
        }
    }
}
