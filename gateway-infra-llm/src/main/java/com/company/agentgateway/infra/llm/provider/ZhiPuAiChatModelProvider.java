package com.company.agentgateway.infra.llm.provider;

import com.company.agentgateway.domain.model.ModelDef;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
import org.springframework.stereotype.Component;

/** 智谱 GLM（spring-ai-starter-model-zhipuai）。 */
@Component
public class ZhiPuAiChatModelProvider implements ChatModelProvider {

    /** baseUrl 若以 /v1 结尾则去掉（Spring AI 各 Api 内部会拼自己的路径前缀，避免重复）。 */
    static String stripV1(String baseUrl) {
        return baseUrl != null && baseUrl.endsWith("/v1")
                ? baseUrl.substring(0, baseUrl.length() - 3) : baseUrl;
    }

    @Override
    public String provider() {
        return "zhipuai";
    }

    @Override
    public boolean supports(String provider) {
        return "zhipuai".equalsIgnoreCase(provider) || "zhipu".equalsIgnoreCase(provider);
    }

    @Override
    public ChatModel create(ModelDef model, String apiKey) {
        ZhiPuAiApi api = ZhiPuAiApi.builder()
                .baseUrl(stripV1(model.endpoint()))
                .apiKey(apiKey)
                .build();
        return new ZhiPuAiChatModel(api,
                ZhiPuAiChatOptions.builder().model(model.modelNameOrId()).build());
    }
}
