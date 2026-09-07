package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.infra.security.ApiKeyStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * /v1/admin/tenants — 列出所有租户（spec §admin-tenants round 57）。
 *
 * <p>从 ApiKeyStore.entries() 聚合：tenantId + 该租户下的 API key 数。
 * 给 SRE / 客服 / 销售「现在系统里有多少租户、各自多少 key」。
 *
 * <p>注意：ApiKeyStore 仅在内存 / 持久化 JSON 文件中存 key，
 * Postgres 表没存 tenant 维度（key 与 tenant 一对多）。所以从 key 反推。
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminTenantsController {

    private final ApiKeyStore apiKeyStore;

    public AdminTenantsController(ApiKeyStore apiKeyStore) {
        this.apiKeyStore = apiKeyStore;
    }

    @GetMapping("/tenants")
    public Map<String, Object> list(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken) {
        if (adminToken == null || adminToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Admin-Token required");
        }
        Map<String, Long> keyCountByTenant = apiKeyStore.entries().stream()
                .collect(Collectors.groupingBy(
                        e -> e.getValue().tenant().value(),
                        Collectors.counting()));
        List<Map<String, Object>> tenants = keyCountByTenant.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tenantId", e.getKey());
                    m.put("activeApiKeys", e.getValue());
                    return m;
                })
                .toList();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", tenants.size());
        resp.put("tenants", tenants);
        return resp;
    }
}