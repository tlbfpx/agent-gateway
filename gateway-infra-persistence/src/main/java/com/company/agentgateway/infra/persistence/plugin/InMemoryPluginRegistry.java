package com.company.agentgateway.infra.persistence.plugin;

import com.company.agentgateway.domain.plugin.Plugin;
import com.company.agentgateway.domain.plugin.PluginCapability;
import com.company.agentgateway.domain.plugin.PluginRegistry;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 插件内存注册（spec 2026-09-02 §wasm-plugins §5 P0）。
 *
 * <p>P0 用 {@link ConcurrentHashMap};PluginManager 在启动时通过 ServiceLoader 加载所有插件。
 * R15 #2 swap Wasm 适配器时,本类不变(只换 Plugin 实例构造路径)。
 */
public class InMemoryPluginRegistry implements PluginRegistry {

    private final ConcurrentMap<String, Plugin> plugins = new ConcurrentHashMap<>();

    @Override
    public Plugin register(Plugin plugin) {
        if (plugin == null || plugin.id() == null) {
            throw new IllegalArgumentException("plugin and id required");
        }
        plugins.put(plugin.id(), plugin);
        return plugin;
    }

    @Override
    public boolean unregister(String id) {
        return plugins.remove(id) != null;
    }

    @Override
    public List<Plugin> listAll() {
        return List.copyOf(plugins.values());
    }

    @Override
    public Optional<Plugin> findById(String id) {
        return Optional.ofNullable(plugins.get(id));
    }

    @Override
    public List<Plugin> findByCapability(PluginCapability capability) {
        return plugins.values().stream()
                .filter(p -> p.capabilities().contains(capability))
                .toList();
    }
}