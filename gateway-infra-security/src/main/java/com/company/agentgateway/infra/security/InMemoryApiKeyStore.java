package com.company.agentgateway.infra.security;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ApiKeyStore 内存实现（默认）。key→binding 存 ConcurrentHashMap。
 * 一期：binding 由配置注入（启动时加载）；二期管理 REST 签发/吊销。
 */
public class InMemoryApiKeyStore implements ApiKeyStore {

    private final Map<String, ApiKeyBinding> store = new ConcurrentHashMap<>();

    @Override
    public java.util.List<java.util.Map.Entry<String, ApiKeyBinding>> entries() {
        return java.util.List.copyOf(store.entrySet());
    }

    /** 注册一个 API Key 绑定（启动配置/测试用）。 */
    public void register(String apiKey, ApiKeyBinding binding) {
        store.put(apiKey, binding);
    }

    /** 吊销（管理 REST 用，一期可选）。 */
    public void revoke(String apiKey) {
        store.remove(apiKey);
    }

    @Override
    public Optional<ApiKeyBinding> findByKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(apiKey))
                .filter(b -> !b.revoked())
                .filter(b -> !b.isExpired(java.time.Instant.now()));
    }
}
