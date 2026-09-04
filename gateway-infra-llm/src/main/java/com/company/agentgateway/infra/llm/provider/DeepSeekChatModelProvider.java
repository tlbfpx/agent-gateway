package com.company.agentgateway.infra.llm.provider;

import com.company.agentgateway.domain.model.ModelDef;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.stereotype.Component;

/** DeepSeek 官方 starter（spring-ai-starter-model-deepseek）。 */
@Component
public class DeepSeekChatModelProvider implements ChatModelProvider {

    /** baseUrl 若以 /v1 结尾则去掉（Spring AI 各 Api 内部会拼自己的路径前缀，避免重复）。 */
    static String stripV1(String baseUrl) {
        return baseUrl != null && baseUrl.endsWith("/v1")
                ? baseUrl.substring(0, baseUrl.length() - 3) : baseUrl;
    }

    @Override
    public String provider() {
        return "deepseek";
    }

    @Override
    public ChatModel create(ModelDef model, String apiKey) {
        DeepSeekApi api = DeepSeekApi.builder()
                .baseUrl(stripV1(model.endpoint()))
                .apiKey(apiKey)
                .build();
        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .defaultOptions(DeepSeekChatOptions.builder().model(model.modelNameOrId()).build())
                .build();
    }
}
