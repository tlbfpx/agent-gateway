package com.company.agentgateway.domain.orchestration;

import java.time.Instant;
import java.util.Optional;

/**
 * 提示缓存出站端口（语义缓存第一步：规范化精确匹配，对标 LiteLLM/Portkey Prompt Cache）。
 *
 * <p>domain 只定义契约；infra 提供内存实现（TTL + 容量上限 + 定时清理）。
 * key 由调用方构造（建议 SHA-256(模型ID + 规范化 prompt + 关键采样参数)）。
 *
 * <p>保守命中原则：仅无工具调用、无多轮 history 的请求允许写入/读取（由编排层判断）。
 */
public interface PromptCachePort {

    /** 读取缓存（过期/未命中返回 empty；实现方应记录命中/未命中指标）。 */
    Optional<CacheEntry> get(String key);

    /** 写入缓存（超出容量时按插入序淘汰最旧条目）。 */
    void put(String key, CacheEntry entry);

    /** 缓存条目：完整回答 + 创建时间 + 命中模型。 */
    record CacheEntry(String answer, Instant createdAt, String model) {}
}
