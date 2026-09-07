package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.application.admin.auth.AdminAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * /v1/admin/sessions — 当前 admin session 计数（spec §admin-sessions round 58）。
 *
 * <p>从 AdminAuthService 暴露 active session 数量。
 * 给 SRE / 客服查「现在有多少 admin 处于登录态」。
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminSessionsController {

    private final AdminAuthService authService;

    public AdminSessionsController(AdminAuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/sessions")
    public Map<String, Object> sessions(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken) {
        if (adminToken == null || adminToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Admin-Token required");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("selfTokenValid", authService.verifyToken(adminToken).isPresent());
        out.put("activeSessions", authService.activeSessionCount());
        return out;
    }
}