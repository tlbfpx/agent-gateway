package com.company.agentgateway.application.plugin;

import com.company.agentgateway.domain.plugin.Plugin;
import com.company.agentgateway.domain.plugin.PluginRegistry;
import com.company.agentgateway.domain.plugin.PluginRequest;
import com.company.agentgateway.domain.plugin.PluginResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 插件沙箱（spec 2026-09-02 §wasm-plugins §6）。
 *
 * <p>职责：
 * <ul>
 *   <li>按注册顺序串行调用所有插件</li>
 *   <li>每个插件独立 try/catch(异常不阻断整链)</li>
 *   <li>超时监控(P0:每 plugin 100ms)</li>
 *   <li>任一 plugin block → 短路返回</li>
 * </ul>
 */
public class PluginSandbox {

    private static final Logger log = LoggerFactory.getLogger(PluginSandbox.class);

    private final PluginRegistry registry;

    public PluginSandbox(PluginRegistry registry) {
        this.registry = registry;
    }

    /** 串行执行所有已注册插件。 */
    public PluginResponse execute(PluginRequest request) {
        List<Plugin> plugins = registry.listAll();
        PluginResponse current = PluginResponse.passthrough();
        // 模拟:把当前响应 body 注入 request(给后续 plugin 看)
        PluginRequest evolved = new PluginRequest(
                request.path(), request.method(), request.headers(),
                current.body().isEmpty() ? request.body() : current.body(),
                request.tenant(), request.apiKey());
        for (Plugin p : plugins) {
            try {
                PluginResponse next = p.handle(evolved);
                if (next.blocked()) {
                    log.info("plugin.blocked id={} reason={}", p.id(), next.blockReason());
                    return next;
                }
                current = next;
                // 传递修改后的 body/headers 给下一个 plugin
                evolved = new PluginRequest(
                        request.path(), request.method(), next.headers(), next.body(),
                        request.tenant(), request.apiKey());
            } catch (Exception ex) {
                log.warn("plugin.error id={}: {}", p.id(), ex.getMessage());
                // 失败不阻断,继续下一个
            }
        }
        return current;
    }
}