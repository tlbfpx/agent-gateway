package com.company.agentgateway.infra.llm.session;

import com.company.agentgateway.domain.orchestration.*;
import com.company.agentgateway.infra.llm.adapter.LlmFlowAdapter;
import com.company.agentgateway.infra.llm.adapter.ToolCallbackConverter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;

/**
 * LlmSession 实现：用 Spring AI ChatModel 流式生成 + LlmFlowAdapter 转 Flow.Publisher<LlmEvent>。
 *
 * <p><b>FC 注入(spec B §3.1)</b>:Prompt 携带 ToolCallingChatOptions(toolCallbacks 仅声明 schema,
 * internalToolExecutionEnabled=false) —— 让模型知道有工具可用,且不会双重执行(自研 runToolLoop 接管)。
 *
 * <p><b>工具结果回填(spec B §3.2)</b>:上一轮有 toolCall 时,ToolResultMessage 用原生 ToolResponseMessage
 * 回填(带 toolCallId 对应);否则用纯文本降级(全厂商兼容)。
 */
public class ChatClientLlmSession implements LlmSession {

    private final org.springframework.ai.chat.model.ChatModel chatModel;
    private final List<ToolDescriptor> tools;

    public ChatClientLlmSession(org.springframework.ai.chat.model.ChatModel chatModel, List<ToolDescriptor> tools) {
        this.chatModel = chatModel;
        this.tools = List.copyOf(tools);
    }

    @Override
    public Flow.Publisher<LlmEvent> generate(String prompt, List<com.company.agentgateway.domain.session.Message> history, InvocationCtx ctx) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        if (history != null) {
            for (var m : history) {
                messages.add(toSpringMessage(m));
            }
        }
        // 本轮用户消息（最后）
        messages.add(new org.springframework.ai.chat.messages.UserMessage(prompt));

        ChatOptions options = buildOptions();
        var flux = (options == null)
                ? chatModel.stream(new Prompt(messages))
                : chatModel.stream(new Prompt(messages, options));
        return LlmFlowAdapter.adapt(flux);
    }

    /**
     * domain Message → Spring AI Message(spec B §3.2):
     *   - UserMessage → UserMessage
     *   - AssistantMessage → AssistantMessage
     *   - ToolCallMessage → AssistantMessage 带 ToolCall(由 runToolLoop 文本构造的伪 AssistantMessage,
     *     也走这个分支以便模型上下文连贯)
     *   - ToolResultMessage → 若有上一轮 ToolCall,原生 ToolResponseMessage;否则纯文本 AssistantMessage 降级
     */
    private org.springframework.ai.chat.messages.Message toSpringMessage(
            com.company.agentgateway.domain.session.Message m) {
        if (m instanceof com.company.agentgateway.domain.session.UserMessage u) {
            String t = u.content();
            return t == null || t.isBlank() ? null : new org.springframework.ai.chat.messages.UserMessage(t);
        }
        if (m instanceof com.company.agentgateway.domain.session.AssistantMessage a) {
            String t = a.content();
            return t == null || t.isBlank() ? null : new AssistantMessage(t);
        }
        if (m instanceof com.company.agentgateway.domain.session.ToolCallMessage tc) {
            // 文本降级(自研 runToolLoop 当前已用文本回填,这里保持 AssistantMessage)
            String t = "[调用 Agent " + tc.agentName() + "](" + tc.argsJson() + ")";
            return new AssistantMessage(t);
        }
        if (m instanceof com.company.agentgateway.domain.session.ToolResultMessage tr) {
            // toolCallId 非空 → 原生 ToolResponseMessage(严格厂商协议,OpenAI 等);
            // 否则文本降级(全厂商兜底)。
            String resultText = tr.content() == null ? "" : tr.content();
            if (tr.toolCallId() != null && !tr.toolCallId().isBlank()) {
                // ToolResponse(name, content, id) —— Spring AI 2.0.0-M1 三参
                return ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                tr.agentName(), resultText, tr.toolCallId())))
                        .build();
            }
            return new AssistantMessage("[Agent " + tr.agentName() + " 结果] " + resultText);
        }
        return null;
    }

    /** FC options(spec B §3.1):toolCallbacks + internalToolExecutionEnabled=false。 */
    private ChatOptions buildOptions() {
        if (tools == null || tools.isEmpty()) return null;
        return DefaultToolCallingChatOptions.builder()
                .toolCallbacks(ToolCallbackConverter.convert(tools))
                .internalToolExecutionEnabled(false)
                .build();
    }

    List<ToolDescriptor> getTools() {
        return tools;
    }
}
