package com.company.agentgateway.domain.orchestration;

import com.company.agentgateway.domain.session.Message;

import java.util.List;
import java.util.concurrent.Flow;

/**
 * LLM 会话领域抽象。infra 桥接到 Spring AI ChatClient + Reactor，并写 Flow↔Flux 适配器。
 *
 * <p>多轮对话：{@link #generate(String, List, InvocationCtx)} 携带会话历史（编排层经
 * ContextWindow 裁剪后传入），LLM 据此具备跨轮记忆。
 */
public interface LlmSession {

    /**
     * 流式生成（带会话历史——多轮记忆）。
     *
     * @param prompt  本轮用户消息
     * @param history 会话历史（已裁剪，含此前各轮 user/assistant/tool 消息；可为空）
     * @param ctx     调用上下文
     */
    Flow.Publisher<LlmEvent> generate(String prompt, List<Message> history, InvocationCtx ctx);

    /** 兼容旧签名（无历史，单轮）。 */
    default Flow.Publisher<LlmEvent> generate(String prompt, InvocationCtx ctx) {
        return generate(prompt, List.of(), ctx);
    }
}
