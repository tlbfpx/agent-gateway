package com.company.agentgateway.domain.orchestration;
import com.company.agentgateway.domain.shared.ModelId;
import java.util.List;

/** 出站端口：构造 LlmSession。由 gateway-infra-llm 实现。domain 不依赖 Spring AI ChatClient。 */
public interface ChatClientPort {
    LlmSession sessionFor(ModelId model, List<ToolDescriptor> tools);
}
