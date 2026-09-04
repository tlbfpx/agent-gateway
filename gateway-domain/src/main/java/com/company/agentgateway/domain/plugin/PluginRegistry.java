package com.company.agentgateway.domain.plugin;

import java.util.List;
import java.util.Optional;

/**
 * 插件注册中心端口（spec 2026-09-02 §wasm-plugins §4）。
 */
public interface PluginRegistry {

    /** 注册一个插件(覆盖式) */
    Plugin register(Plugin plugin);

    /** 按 id 注销 */
    boolean unregister(String id);

    /** 列所有插件 */
    List<Plugin> listAll();

    /** 按 id 查 */
    Optional<Plugin> findById(String id);

    /** 按 capability 过滤 */
    List<Plugin> findByCapability(PluginCapability capability);
}