package com.company.agentgateway.infra.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 配置状态查询端点(Sprint 1 P0 §3.9):供 UI「Config Reloader」总览页 +
 * 各配置页的「状态徽章」使用。
 *
 * <ul>
 *   <li>GET /v1/admin/config/status              — 所有配置的状态</li>
 *   <li>GET /v1/admin/config/status/{name}      — 单个配置的状态</li>
 *   <li>GET /v1/admin/config/status/recent      — 最近事件流(诊断用)</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin/config")
public class ConfigStatusController {

    private final ConfigSourceRegistry registry;

    public ConfigStatusController(ConfigSourceRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/status")
    public List<ConfigSourceRegistry.Status> all() {
        return registry.allStatuses();
    }

    @GetMapping("/status/{name}")
    public ConfigSourceRegistry.Status one(@PathVariable String name) {
        return registry.statusOf(name);
    }

    @GetMapping("/status/recent")
    public Map<String, Object> recent() {
        return Map.of(
                "events", registry.recentEvents(),
                "count", registry.recentEvents().size());
    }
}