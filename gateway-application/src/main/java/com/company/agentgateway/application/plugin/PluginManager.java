package com.company.agentgateway.application.plugin;

import com.company.agentgateway.domain.plugin.Plugin;
import com.company.agentgateway.domain.plugin.PluginRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;

/**
 * 插件管理器（spec 2026-09-02 §wasm-plugins §7）。
 *
 * <p>启动时通过 {@link ServiceLoader} 发现 META-INF/services 注册的所有插件,
 * 注入 {@link PluginRegistry};运行时可动态 register/unregister(测试用)。
 */
public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final PluginRegistry registry;

    public PluginManager(PluginRegistry registry) {
        this.registry = registry;
    }

    /** 启动加载：ServiceLoader + 内置插件硬编码 */
    public void bootstrap() {
        // ServiceLoader 发现
        ServiceLoader<Plugin> loader = ServiceLoader.load(Plugin.class);
        int discovered = 0;
        for (Plugin p : loader) {
            try {
                registry.register(p);
                discovered++;
                log.info("plugin.bootstrap.discovered id={} name={}", p.id(), p.descriptor().name());
            } catch (Exception ex) {
                log.warn("plugin.bootstrap.failed id={}: {}", p.id(), ex.getMessage());
            }
        }
        // 内置 hardcoded 兜底(SPI 没注册时)
        registerBuiltinIfAbsent(new com.company.agentgateway.application.plugin.builtin.HeaderInjectPlugin());
        registerBuiltinIfAbsent(new com.company.agentgateway.application.plugin.builtin.CompressPlugin());
        registerBuiltinIfAbsent(new com.company.agentgateway.application.plugin.builtin.AuditPlugin());
        registerBuiltinIfAbsent(new com.company.agentgateway.application.plugin.builtin.RateLimitPlugin());

        log.info("plugin.bootstrap.complete total={} discovered={}", registry.listAll().size(), discovered);
    }

    private void registerBuiltinIfAbsent(Plugin p) {
        if (registry.findById(p.id()).isEmpty()) {
            registry.register(p);
        }
    }
}