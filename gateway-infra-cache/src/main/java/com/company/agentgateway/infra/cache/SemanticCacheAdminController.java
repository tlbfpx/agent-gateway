package com.company.agentgateway.infra.cache;

import com.company.agentgateway.domain.cache.SemanticCacheFacade;
import com.company.agentgateway.domain.cache.SemanticCachePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 语义缓存管理端点(Sprint 4 P0):供 UI「/admin/cache」页面使用。
 *
 * <ul>
 *   <li>GET /v1/admin/cache/stats?tenant=… — 命中率 + 节省成本</li>
 *   <li>GET /v1/admin/cache/top-queries?tenant=…&limit=20 — 高频命中</li>
 *   <li>POST /v1/admin/cache/invalidate?tenant=… — 租户级失效</li>
 *   <li>POST /v1/admin/cache/purge?olderThanDays=30 — 物理清理过期记录</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin/cache")
@ConditionalOnBean(SemanticCachePort.class)
public class SemanticCacheAdminController {

    private final SemanticCachePort port;
    private final SemanticCacheFacade facade;

    public SemanticCacheAdminController(SemanticCachePort port, SemanticCacheFacade facade) {
        this.port = port;
        this.facade = facade;
    }

    @GetMapping("/stats")
    public SemanticCachePort.Stats stats(@RequestParam(defaultValue = "default") String tenant) {
        return port.stats(tenant);
    }

    @GetMapping("/top-queries")
    public List<SemanticCachePort.TopQuery> topQueries(
            @RequestParam(defaultValue = "default") String tenant,
            @RequestParam(defaultValue = "20") int limit) {
        return port.topQueries(tenant, limit);
    }

    @PostMapping("/invalidate")
    public Map<String, Object> invalidate(@RequestParam(defaultValue = "default") String tenant) {
        int removed = port.invalidateByTenant(tenant, null);
        return Map.of("tenant", tenant, "removed", removed);
    }

    @PostMapping("/purge")
    public Map<String, Object> purge(@RequestParam(defaultValue = "30") int olderThanDays) {
        java.time.Instant cutoff = java.time.Instant.now().minus(java.time.Duration.ofDays(olderThanDays));
        int removed = port.purgeExpired(cutoff);
        return Map.of("cutoff", cutoff.toString(), "removed", removed);
    }
}