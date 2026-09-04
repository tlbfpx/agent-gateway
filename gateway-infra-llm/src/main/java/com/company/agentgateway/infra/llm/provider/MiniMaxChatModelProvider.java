package com.company.agentgateway.infra.llm.provider;

import com.company.agentgateway.domain.model.ModelDef;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.minimax.MiniMaxChatModel;
import org.springframework.ai.minimax.MiniMaxChatOptions;
import org.springframework.ai.minimax.api.MiniMaxApi;
import org.springframework.stereotype.Component;

/** MiniMax（spring-ai-starter-model-minimax）。MiniMaxApi 用构造器（2.0 无 builder）。 */
@Component
public class MiniMaxChatModelProvider implements ChatModelProvider {

    @Override
    public String provider() {
        return "minimax";
    }

    @Override
    public ChatModel create(ModelDef model, String apiKey) {
        // MiniMaxApi 内部会在 baseUrl 后拼 /v1/text/chatcompletion_v2——endpoint 若已带 /v1 需去掉，避免 /v1/v1
        String baseUrl = model.endpoint();
        if (baseUrl.endsWith("/v1")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 3);
        }
        MiniMaxApi api = new MiniMaxApi(baseUrl, apiKey);
        return new MiniMaxChatModel(api,
                MiniMaxChatOptions.builder().model(model.modelNameOrId()).build());
    }
}
