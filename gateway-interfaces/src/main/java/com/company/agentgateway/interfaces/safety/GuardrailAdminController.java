package com.company.agentgateway.interfaces.safety;

import com.company.agentgateway.domain.safety.GuardrailPolicy;
import com.company.agentgateway.infra.security.safety.DefaultGuardrailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Guardrail 运营端点(Round 9):
 * - GET  /v1/admin/guardrails/policy — 当前策略
 * - POST /v1/admin/guardrails/policy — 热更新策略
 * - GET  /v1/admin/guardrails/stats  — 命中率/阻断数(stats 完整实现留 Round 9 后续)
 */
@RestController
@RequestMapping("/v1/admin/guardrails")
public class GuardrailAdminController {

    private final DefaultGuardrailService service;

    public GuardrailAdminController(DefaultGuardrailService service) {
        this.service = service;
    }

    @GetMapping("/policy")
    public GuardrailPolicy currentPolicy() {
        return service.currentPolicy();
    }

    @PostMapping("/policy")
    public Map<String, Object> updatePolicy(@RequestBody GuardrailPolicy policy) {
        service.updatePolicy(policy);
        return Map.of("status", "updated", "mode", policy.mode().name());
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        // 完整 stats 接入 metrics 模块留 Round 9 后续 — 先返回基本结构
        return Map.of("status", "ok", "hits", 0, "blocks", 0);
    }
}