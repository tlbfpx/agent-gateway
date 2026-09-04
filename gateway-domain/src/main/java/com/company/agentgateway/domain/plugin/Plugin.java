package com.company.agentgateway.domain.plugin;

import java.util.Set;

/**
 * 插件 SPI（spec 2026-09-02 §wasm-plugins §3.5）。
 *
 * <p>P0 Java SPI(ServiceLoader 发现);R15 #2 swap Chicory Wasm 适配器。
 *
 * <p>插件实现须:
 * <ol>
 *   <li>有 public 无参构造</li>
 *   <li>在 {@code META-INF/services/com.company.agentgateway.domain.plugin.Plugin}
 *       注册 FQCN</li>
 * </ol>
 */
public interface Plugin {

    /** 唯一 ID。 */
    String id();

    /** 描述符。 */
    PluginDescriptor descriptor();

    /** 处理请求,返回修改后的响应。 */
    PluginResponse handle(PluginRequest request);

    /** 声明的能力集合(用于路由优化) */
    default Set<PluginCapability> capabilities() {
        return descriptor().capabilities();
    }
}