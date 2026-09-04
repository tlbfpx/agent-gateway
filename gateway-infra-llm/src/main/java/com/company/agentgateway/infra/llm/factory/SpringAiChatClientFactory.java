package com.company.agentgateway.infra.llm.factory;

import com.company.agentgateway.domain.model.ModelDef;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.infra.llm.provider.ChatModelProvider;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.ai.chat.model.ChatModel;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * ChatModel 工厂（可插拔）：经 {@link ChatModelProvider} SPI 路由到厂商实现。
 *
 * <p>新增厂商 = 新增一个 ChatModelProvider @Component，本类零改动（OCP）。
 * Caffeine 缓存（ModelId key）；配置变更由 ModelRegistry listener 触发 invalidate。
 */
public class SpringAiChatClientFactory implements ChatClientFactory {

    private final SecretResolver secretResolver;
    private final List<ChatModelProvider> providers;
    private final Cache<ModelId, ChatModel> cache;

    public SpringAiChatClientFactory(SecretResolver secretResolver, List<ChatModelProvider> providers) {
        this.secretResolver = secretResolver;
        this.providers = List.copyOf(providers);
        this.cache = Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterAccess(Duration.ofHours(1))
                .build();
    }

    @Override
    public ChatModel getChatModel(ModelDef model) {
        return cache.get(model.id(), id -> createChatModel(model));
    }

    @Override
    public void invalidate(ModelId modelId) {
        cache.invalidate(modelId);
    }

    @Override
    public void invalidateAll() {
        cache.invalidateAll();
    }

    private ChatModel createChatModel(ModelDef model) {
        String apiKey = secretResolver.resolve(model.apiKeyRef());
        String provider = model.provider() == null ? "" : model.provider().toLowerCase();

        Optional<ChatModelProvider> matched = providers.stream()
                .filter(p -> p.supports(provider))
                .findFirst();
        return matched.map(p -> p.create(model, apiKey))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported provider: " + model.provider()
                                + "（可用: " + providers.stream().map(ChatModelProvider::provider).toList() + "）"));
    }
}
