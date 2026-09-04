package com.company.agentgateway.domain.plugin;

/**
 * 插件能力枚举（spec 2026-09-02 §wasm-plugins §3.1）。
 *
 * <p>每种能力对应 request/response 处理的一个钩子点。
 */
public enum PluginCapability {
    HEADER_INJECT,    // 修改请求/响应 headers
    BODY_TRANSFORM,    // 修改 body
    RATE_LIMIT,        // 限速阻断
    AUDIT,             // 审计记录
    COMPRESS,          // body 压缩
    LOG                // 自定义日志
}