package com.company.agentgateway.application.dataset.judge;

import com.company.agentgateway.domain.dataset.JudgeLlmPort;
import com.company.agentgateway.domain.orchestration.ChatClientPort;
import com.company.agentgateway.domain.orchestration.LlmEvent;
import com.company.agentgateway.domain.orchestration.LlmSession;
import com.company.agentgateway.domain.session.UserMessage;
import com.company.agentgateway.domain.shared.ModelId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default {@link JudgeLlmPort} using {@link ChatClientPort}（spec 2026-09-02 §llm-judge §6 R17 #1）。
 *
 * <p>P0:订阅 LlmEvent 流,提取 content delta 拼接成完整响应(单次同步)。
 * 支持任意 ChatClientPort 实现(gpt-4o / claude / 自建 / mock)。
 */
public class DefaultJudgeLlmPort implements JudgeLlmPort {

    private static final Logger log = LoggerFactory.getLogger(DefaultJudgeLlmPort.class);

    private final ChatClientPort chatClient;

    public DefaultJudgeLlmPort(ChatClientPort chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, String model, double temperature) {
        ModelId mid = (model == null || model.isBlank()) ? new ModelId("gpt-4o-mini") : new ModelId(model);
        LlmSession session = chatClient.sessionFor(mid, List.of());
        com.company.agentgateway.domain.orchestration.InvocationCtx ctx =
                com.company.agentgateway.domain.orchestration.InvocationCtx.NOOP;
        List<com.company.agentgateway.domain.session.Message> history = List.of(new UserMessage(userPrompt));

        AtomicReference<String> fullContent = new AtomicReference<>("");
        AtomicReference<Throwable> error = new AtomicReference<>();

        session.generate(userPrompt, history, ctx).subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }
            @Override public void onNext(LlmEvent e) {
                if (e instanceof LlmEvent.Delta d) {
                    fullContent.updateAndGet(v -> v + d.content());
                }
            }
            @Override public void onError(Throwable t) {
                error.set(t);
            }
            @Override public void onComplete() { /* done */ }
        });

        if (error.get() != null) {
            throw new RuntimeException("LLM judge call failed", error.get());
        }
        return fullContent.get();
    }

    @Override
    public boolean isAvailable() {
        return chatClient != null;
    }
}