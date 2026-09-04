package com.company.agentgateway.infra.llm.port;

import com.company.agentgateway.domain.model.ModelDef;
import com.company.agentgateway.domain.orchestration.ChatClientPort;
import com.company.agentgateway.domain.orchestration.LlmSession;
import com.company.agentgateway.domain.orchestration.ToolDescriptor;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.infra.llm.factory.ChatClientFactory;
import com.company.agentgateway.infra.llm.model.ModelRegistry;
import com.company.agentgateway.infra.llm.session.ChatClientLlmSession;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

/**
 * ChatClientPort 实现：sessionFor(model, tools) → LlmSession。
 * 流程：registry 查 ModelDef → failover 解析（可能切到 fallbackToolModel）→ factory 取 ChatModel → 包装为 ChatClientLlmSession。
 */
public class ChatClientPortImpl implements ChatClientPort {

    private final ModelRegistry registry;
    private final ChatClientFactory factory;
    private final ModelCapabilityFailover failover;
    private final WeightedModelSelector weightedSelector;

    public ChatClientPortImpl(ModelRegistry registry, ChatClientFactory factory, ModelCapabilityFailover failover) {
        this(registry, factory, failover, new WeightedModelSelector(id -> registry.getModel(id)));
    }

    public ChatClientPortImpl(ModelRegistry registry, ChatClientFactory factory,
                               ModelCapabilityFailover failover, WeightedModelSelector weightedSelector) {
        this.registry = registry;
        this.factory = factory;
        this.failover = failover;
        this.weightedSelector = weightedSelector;
    }

    @Override
    public LlmSession sessionFor(ModelId model, List<ToolDescriptor> tools) {
        // 灰度路由（spec §5.5 二期）：同 displayName 组按 weight 分流；无组=精确匹配
        ModelDef selected = weightedSelector.select(model, registry.listModels());
        if (selected == null) {
            throw new IllegalArgumentException("Model not found: " + model);
        }
        ModelDef resolved = failover.resolve(selected, tools);
        ChatModel chatModel = factory.getChatModel(resolved);
        return new ChatClientLlmSession(chatModel, List.copyOf(tools));
    }
}
