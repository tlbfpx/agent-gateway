package com.company.agentgateway.infra.llm.port;

import com.company.agentgateway.domain.model.Capability;
import com.company.agentgateway.domain.model.ModelDef;
import com.company.agentgateway.domain.orchestration.LlmSession;
import com.company.agentgateway.domain.orchestration.ToolDescriptor;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.infra.llm.factory.ChatClientFactory;
import com.company.agentgateway.infra.llm.model.ModelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ChatClientPortImplTest {

    private static final ModelId SELECTED_ID = new ModelId("basic");
    private static final ModelId FALLBACK_ID = new ModelId("qwen-max");

    private ModelRegistry registry;
    private ChatClientFactory factory;
    private ChatModel chatModel;
    private ChatClientPortImpl port;

    private ModelDef model(String id, boolean supportsFc) {
        return new ModelDef(new ModelId(id), "openai-compatible", id, "https://endpoint", "${SECRET:K}",
                supportsFc ? Set.of(Capability.FUNCTION_CALLING) : Set.of(),
                4096, BigDecimal.ZERO, BigDecimal.ZERO, true, List.of("all"));
    }

    private List<ToolDescriptor> tools() {
        return List.of(new ToolDescriptor("t", "d", "{}"));
    }

    @BeforeEach
    void setUp() {
        registry = mock(ModelRegistry.class);
        factory = mock(ChatClientFactory.class);
        chatModel = mock(ChatModel.class);
        var failover = new ModelCapabilityFailover(registry, FALLBACK_ID);
        port = new ChatClientPortImpl(registry, factory, failover);
    }

    @Test
    void sessionFor_返回LlmSession() {
        var selected = model("basic", true);
        when(registry.getModel(SELECTED_ID)).thenReturn(Optional.of(selected));
        when(factory.getChatModel(selected)).thenReturn(chatModel);

        LlmSession session = port.sessionFor(SELECTED_ID, tools());

        assertThat(session).isNotNull();
        verify(factory).getChatModel(selected);
    }

    @Test
    void sessionFor_模型不存在抛异常() {
        when(registry.getModel(SELECTED_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> port.sessionFor(SELECTED_ID, tools()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Model not found");
        verifyNoInteractions(factory);
    }

    @Test
    void sessionFor_缺FC有工具时failover到fallback() {
        var selected = model("basic", false);          // 缺 FC
        var fallback = model("qwen-max", true);        // fallback 有 FC
        when(registry.getModel(SELECTED_ID)).thenReturn(Optional.of(selected));
        when(registry.getModel(FALLBACK_ID)).thenReturn(Optional.of(fallback));
        when(factory.getChatModel(fallback)).thenReturn(chatModel);

        port.sessionFor(SELECTED_ID, tools());

        // 关键：factory 收到的是 fallback 的 ModelDef（而非 selected）
        ArgumentCaptor<ModelDef> captor = ArgumentCaptor.forClass(ModelDef.class);
        verify(factory).getChatModel(captor.capture());
        assertThat(captor.getValue().id()).isEqualTo(FALLBACK_ID);
        verify(factory, never()).getChatModel(selected);
    }

    @Test
    void sessionFor_无工具时不触发failover() {
        var selected = model("basic", false);          // 缺 FC 但无工具
        when(registry.getModel(SELECTED_ID)).thenReturn(Optional.of(selected));
        when(factory.getChatModel(selected)).thenReturn(chatModel);

        port.sessionFor(SELECTED_ID, List.of());

        verify(factory).getChatModel(selected);
    }
}
