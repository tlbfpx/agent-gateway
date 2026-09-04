package com.company.agentgateway.infra.llm.factory;

import com.company.agentgateway.domain.model.Capability;
import com.company.agentgateway.domain.model.ModelDef;
import com.company.agentgateway.domain.shared.ModelId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.openai.OpenAiChatModel;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SpringAiChatClientFactory 的单元测试。
 * 测试缓存失效、provider 分发，避免真实网络调用。
 */
class SpringAiChatClientFactoryTest {

    private SecretResolver mockResolver;
    private SpringAiChatClientFactory factory;

    @BeforeEach
    void setUp() {
        mockResolver = mock(SecretResolver.class);
        when(mockResolver.resolve(anyString())).thenReturn("sk-test-123");
        factory = new SpringAiChatClientFactory(mockResolver, java.util.List.of(
                new com.company.agentgateway.infra.llm.provider.DeepSeekChatModelProvider(),
                new com.company.agentgateway.infra.llm.provider.OpenAiChatModelProvider(),
                new com.company.agentgateway.infra.llm.provider.ZhiPuAiChatModelProvider(),
                new com.company.agentgateway.infra.llm.provider.MiniMaxChatModelProvider()));
    }

    @Test
    void 同一ModelDef返回相同实例_Caffeine缓存生效() {
        ModelDef model = createModelDef("deepseek", "deepseek-chat");
        ChatModel first = factory.getChatModel(model);
        ChatModel second = factory.getChatModel(model);

        assertThat(first).isSameAs(second);
    }

    @Test
    void 不同ModelDef返回不同实例() {
        ModelDef model1 = createModelDef("deepseek", "deepseek-chat");
        ModelDef model2 = createModelDef("openai", "gpt-4");

        ChatModel chat1 = factory.getChatModel(model1);
        ChatModel chat2 = factory.getChatModel(model2);

        assertThat(chat1).isNotSameAs(chat2);
    }

    @Test
    void invalidate后获取新实例() {
        ModelDef model = createModelDef("deepseek", "deepseek-chat");
        ChatModel first = factory.getChatModel(model);

        factory.invalidate(model.id());

        ChatModel second = factory.getChatModel(model);
        assertThat(first).isNotSameAs(second);
    }

    @Test
    void invalidateAll清空所有缓存() {
        ModelDef model1 = createModelDef("deepseek", "deepseek-chat");
        ModelDef model2 = createModelDef("openai", "gpt-4");

        ChatModel first1 = factory.getChatModel(model1);
        ChatModel first2 = factory.getChatModel(model2);

        factory.invalidateAll();

        ChatModel second1 = factory.getChatModel(model1);
        ChatModel second2 = factory.getChatModel(model2);

        assertThat(first1).isNotSameAs(second1);
        assertThat(first2).isNotSameAs(second2);
    }

    @Test
    void deepseekProvider创建DeepSeekChatModel() {
        ModelDef model = createModelDef("deepseek", "deepseek-chat");
        ChatModel chatModel = factory.getChatModel(model);

        assertThat(chatModel).isNotNull();
        assertThat(chatModel).isInstanceOf(DeepSeekChatModel.class);
    }

    @Test
    void openaiProvider创建OpenAiChatModel() {
        ModelDef model = createModelDef("openai", "gpt-4");
        ChatModel chatModel = factory.getChatModel(model);

        assertThat(chatModel).isNotNull();
        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void openaiCompatibleProvider也创建OpenAiChatModel() {
        ModelDef model = new ModelDef(
                new ModelId("gpt-4"),
                "openai-compatible",
                "GPT-4 Compatible",
                "https://api.example.com/v1",
                "sk-test-123",
                Set.of(Capability.FUNCTION_CALLING),
                128000,
                new BigDecimal("0.001"),
                new BigDecimal("0.002"),
                true,
                List.of()
        );

        ChatModel chatModel = factory.getChatModel(model);

        assertThat(chatModel).isNotNull();
        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void 未知Provider抛异常() {
        ModelDef model = createModelDef("unknown-provider", "unknown-model");

        assertThatThrownBy(() -> factory.getChatModel(model))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported provider");
    }

    private ModelDef createModelDef(String provider, String modelName) {
        return new ModelDef(
                new ModelId(modelName),
                provider,
                modelName + " Display",
                "https://api." + provider + ".com",
                "sk-test-123",
                Set.of(Capability.FUNCTION_CALLING),
                128000,
                new BigDecimal("0.001"),
                new BigDecimal("0.002"),
                true,
                List.of()  // 空列表而非 null
        );
    }
}
