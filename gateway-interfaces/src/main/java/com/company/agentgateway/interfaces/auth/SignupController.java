package com.company.agentgateway.interfaces.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 自助注册 REST端（spec 2026-09-04 §self-serve-signup §6）。
 *
 * <p>{@code POST /v1/auth/signup} 公开（无需鉴权），用于把 demo 升级为正式账号。
 * 失败码：
 * <ul>
 *   <li>400 — 输入校验失败（email/password/companyName 非法）</li>
 *   <li>409 — email 在该 tenant 已被注册</li>
 *   <li>500 — 后端 bcrypt / login 失败</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/auth")
public class SignupController {

    private final SignupService signupService;

    public SignupController(SignupService signupService) {
        this.signupService = signupService;
    }

    @PostMapping("/signup")
    public SignupResult signup(@RequestBody Map<String, Object> body) {
        String email = stringOrNull(body, "email");
        String password = stringOrNull(body, "password");
        String companyName = stringOrNull(body, "companyName");
        try {
            return signupService.signup(email, password, companyName);
        } catch (SignupService.EmailAlreadyExistsException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private static String stringOrNull(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : v.toString();
    }
}