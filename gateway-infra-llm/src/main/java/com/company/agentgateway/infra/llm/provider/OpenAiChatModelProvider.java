package com.company.agentgateway.infra.llm.provider;

import com.company.agentgateway.domain.model.ModelDef;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.List;

/** OpenAI / OpenAI 兼容协议（DeepSeek 兼容模式、其他兼容端点的兜底）。 */
@Component
public class OpenAiChatModelProvider implements ChatModelProvider {

    /** baseUrl 若以 /v1 结尾则去掉（Spring AI 各 Api 内部会拼自己的路径前缀，避免重复）。 */
    static String stripV1(String baseUrl) {
        return baseUrl != null && baseUrl.endsWith("/v1")
                ? baseUrl.substring(0, baseUrl.length() - 3) : baseUrl;
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public boolean supports(String provider) {
        // openai 与 openai-compatible 都路由到这里（兼容协议）
        return "openai".equalsIgnoreCase(provider) || "openai-compatible".equalsIgnoreCase(provider);
    }

    @Override
    public ChatModel create(ModelDef model, String apiKey) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(stripV1(model.endpoint()))
                .apiKey(apiKey)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model.modelNameOrId()).build())
                .build();
    }
}
