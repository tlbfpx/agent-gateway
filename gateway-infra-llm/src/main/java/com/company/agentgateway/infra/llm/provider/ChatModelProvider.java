package com.company.agentgateway.infra.llm.provider;

import com.company.agentgateway.domain.model.ModelDef;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 可插拔模型提供方 SPI（spec §5.5.3 多 Provider 装配）。
 *
 * <p>每个厂商一个实现（Spring @Component），SpringAiChatClientFactory 经
 * {@link #supports(String)} 自动路由。新增厂商 = 新增一个实现类，零改动 Factory。
 *
 * <p>provider 值（小写）与模型配置 {@code gateway.models[].provider} 对应：
 * deepseek / openai / openai-compatible / zhipuai / minimax / dashscope。
 */
public interface ChatModelProvider {

    /** 本提供方处理的 provider 标识（小写）。 */
    String provider();

    /** 是否处理该 provider 值。默认精确匹配 {@link #provider()}。 */
    default boolean supports(String provider) {
        return provider() .equalsIgnoreCase(provider);
    }

    /** 按模型定义构造 Spring AI ChatModel。apiKey 已解析为明文。 */
    ChatModel create(ModelDef model, String apiKey);
}
