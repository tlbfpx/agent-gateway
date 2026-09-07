package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.application.admin.auth.AdminAuthService;
import com.company.agentgateway.domain.iam.admin.AdminUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * /v1/admin/me — 当前 Admin 自查询（spec §whoami round 48）。
 *
 * <p>用 X-Admin-Token 查 session → 返回当前 AdminUser 资料。
 * 给集成方 / 客服 / Ops 自查「现在拿的是谁的 token 在用」。
 */
@RestController
@RequestMapping("/v1/admin")
public class WhoAmIController {

    private final AdminAuthService authService;

    public WhoAmIController(AdminAuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/me")
    public Map<String, Object> me(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken) {
        if (adminToken == null || adminToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Admin-Token required");
        }
        AdminUser user = authService.findByToken(adminToken);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", user.id());
        out.put("email", user.email());
        out.put("name", user.name());
        out.put("role", user.role().name());
        out.put("tenantId", user.tenantId());
        if (user.createdAt() != null) out.put("createdAt", user.createdAt().toString());
        return out;
    }
}