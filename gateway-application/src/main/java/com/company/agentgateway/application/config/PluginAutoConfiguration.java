package com.company.agentgateway.application.config;

import com.company.agentgateway.application.plugin.PluginManager;
import com.company.agentgateway.application.plugin.PluginSandbox;
import com.company.agentgateway.domain.plugin.Plugin;
import com.company.agentgateway.domain.plugin.PluginRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 插件系统应用层装配（Round 15 §wasm-plugins）。
 *
 * <p>Registry / Plugin[] 由 {@code gateway-infra-persistence} 提供(SPI + 内置 hardcoded)。
 * 本配置只装配应用层 Manager / Sandbox 与 PostConstruct 启动钩子。
 */
@Configuration
public class PluginAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(PluginManager.class)
    public PluginManager pluginManager(PluginRegistry registry, Plugin[] serviceLoaderPlugins) {
        PluginManager mgr = new PluginManager(registry);
        // 用 ServiceLoader 发现的插件预填(可选;boot 时仍会再调一次 bootstrap)
        for (Plugin p : serviceLoaderPlugins) {
            if (registry.findById(p.id()).isEmpty()) {
                registry.register(p);
            }
        }
        return mgr;
    }

    @Bean
    @ConditionalOnMissingBean(PluginSandbox.class)
    public PluginSandbox pluginSandbox(PluginRegistry registry) {
        return new PluginSandbox(registry);
    }

    /** 启动后立即重新发现并注册所有插件 */
    @Bean
    public PluginBootstrapper pluginBootstrapper(PluginManager manager) {
        return new PluginBootstrapper(manager);
    }

    public static class PluginBootstrapper {
        private final PluginManager manager;
        public PluginBootstrapper(PluginManager manager) { this.manager = manager; }
        @PostConstruct
        public void init() { manager.bootstrap(); }
    }
}