package com.company.agentgateway.interfaces.chat;

import com.company.agentgateway.infra.llm.model.ModelRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 健康检查端点（spec §23.2）。无鉴权（Liveness/Readiness 探针用）。 */
@RestController
public class HealthController {

    private final ObjectProvider<ModelRegistry> modelRegistry;

    public HealthController(ObjectProvider<ModelRegistry> modelRegistry) {
        this.modelRegistry = modelRegistry;
    }

    /** Liveness：进程存活即 UP（LB 摘除用）。 */
    @GetMapping("/v1/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP");
    }

    /** Readiness：关键依赖检查（ModelRegistry 空表=未配置模型，不接流量）。 */
    @GetMapping("/v1/ready")
    public org.springframework.http.ResponseEntity<Map<String, Object>> ready() {
        ModelRegistry registry = modelRegistry.getIfAvailable();
        boolean modelsReady = registry != null && !registry.listModels().isEmpty();
        Map<String, Object> body = Map.of(
                "status", modelsReady ? "READY" : "NOT_READY",
                "checks", Map.of("modelRegistry", modelsReady ? "UP" : "EMPTY"));
        // 模型表为空 = 尚未配置任何模型 → 不接流量（首次部署保护）
        return modelsReady
                ? org.springframework.http.ResponseEntity.ok(body)
                : org.springframework.http.ResponseEntity.status(503).body(body);
    }
}
