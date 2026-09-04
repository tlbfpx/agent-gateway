package com.company.agentgateway.infra.llm.factory;

import com.company.agentgateway.domain.model.ModelDef;
import com.company.agentgateway.domain.shared.ModelId;
import org.springframework.ai.chat.model.ChatModel;

/**
 * ChatModel 工厂接口。
 * 负责按 ModelDef 装配对应的 Spring AI ChatModel，并缓存复用。
 */
public interface ChatClientFactory {

    /**
     * 获取或创建 ChatModel。
     * @param model 模型定义
     * @return Spring AI ChatModel 实例
     */
    ChatModel getChatModel(ModelDef model);

    /**
     * 使指定 ModelId 的缓存失效。
     * @param modelId 模型 ID
     */
    void invalidate(ModelId modelId);

    /**
     * 清空所有缓存。
     */
    void invalidateAll();
}
