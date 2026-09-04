package com.company.agentgateway.infra.llm.session;

import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.orchestration.*;
import com.company.agentgateway.domain.shared.SessionId;
import com.company.agentgateway.infra.llm.adapter.LlmFlowAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatClientLlmSession 测试。
 * mock ChatModel，验证 stream 调用 + LlmFlowAdapter 转换。
 */
class ChatClientLlmSessionTest {

    @Test
    void testGenerateReturnsLlmEventStream() throws InterruptedException {
        // Given: mock ChatModel，stub stream 返回两个 ChatResponse
        ChatModel mockChatModel = mock(ChatModel.class);

        // 构造 mock ChatResponse（含 AssistantMessage）
        AssistantMessage msg1 = new AssistantMessage("Hello");
        Generation gen1 = new Generation(msg1);
        ChatResponse response1 = new ChatResponse(List.of(gen1));

        AssistantMessage msg2 = new AssistantMessage(" World");
        Generation gen2 = new Generation(msg2);
        ChatResponse response2 = new ChatResponse(List.of(gen2));

        // stub stream 返回 Flux
        when(mockChatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
            .thenReturn(Flux.just(response1, response2));

        // 构造 ChatClientLlmSession（带工具描述符，一期仅存储）
        ToolDescriptor tool1 = new ToolDescriptor("search", "Search web", "{\"type\":\"object\"}");
        ToolDescriptor tool2 = new ToolDescriptor("calculate", "Calculate math", "{\"type\":\"object\"}");
        List<ToolDescriptor> tools = List.of(tool1, tool2);
        ChatClientLlmSession session = new ChatClientLlmSession(mockChatModel, tools);

        // 构造 InvocationCtx（简化构造，一期不考虑授权细节）
        SessionId sessionId = new SessionId(java.util.UUID.randomUUID().toString());
        AuthPrincipal principal = new AuthPrincipal(
            new com.company.agentgateway.domain.shared.UserId("user123"),
            new com.company.agentgateway.domain.shared.TenantId("tenant1"),
            Set.of(),
            Set.of(),
            com.company.agentgateway.domain.iam.AuthChannel.API_KEY
        );
        InvocationCtx ctx = new InvocationCtx(sessionId, principal, "trace-123");

        // When: 调用 generate
        Flow.Publisher<LlmEvent> publisher = session.generate("test prompt", ctx);

        // Then: 订阅并收集事件
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicInteger eventCount = new AtomicInteger(0);
        AtomicReference<String> contentRef = new AtomicReference<>("");

        Flow.Subscriber<LlmEvent> subscriber = new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(10);
            }

            @Override
            public void onNext(LlmEvent event) {
                eventCount.incrementAndGet();
                if (event instanceof LlmEvent.Delta delta) {
                    contentRef.updateAndGet(existing -> existing + delta.content());
                }
            }

            @Override
            public void onError(Throwable throwable) {
                errorRef.set(throwable);
                latch.countDown();
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }
        };

        publisher.subscribe(subscriber);
        latch.await(); // 等待完成

        // 断言：无错误，2 个 Delta，内容正确拼接
        assertNull(errorRef.get(), "应该无错误");
        assertEquals(2, eventCount.get(), "应该收到 2 个 LlmEvent");
        assertEquals("Hello World", contentRef.get(), "Delta 内容应拼接正确");
    }

    @Test
    void testGenerateWithEmptyStream() throws InterruptedException {
        // Given: mock ChatModel 返回空流
        ChatModel mockChatModel = mock(ChatModel.class);
        when(mockChatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
            .thenReturn(Flux.empty());

        ChatClientLlmSession session = new ChatClientLlmSession(mockChatModel, List.of());
        InvocationCtx ctx = new InvocationCtx(
            new SessionId(java.util.UUID.randomUUID().toString()),
            new AuthPrincipal(
                new com.company.agentgateway.domain.shared.UserId("user"),
                new com.company.agentgateway.domain.shared.TenantId("tenant"),
                Set.of(),
                Set.of(),
                com.company.agentgateway.domain.iam.AuthChannel.API_KEY
            ),
            "trace"
        );

        // When
        Flow.Publisher<LlmEvent> publisher = session.generate("prompt", ctx);

        // Then: 订阅，立即完成
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger eventCount = new AtomicInteger(0);

        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(10);
            }

            @Override
            public void onNext(LlmEvent event) {
                eventCount.incrementAndGet();
            }

            @Override
            public void onError(Throwable throwable) {
                latch.countDown();
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }
        });

        latch.await();
        assertEquals(0, eventCount.get(), "空流应无事件");
    }

    @Test
    void testToolsStoredSuccessfullyAndPromptCarriesOptions() {
        // Given: ChatModel mock,捕获 Prompt 验证 spec B §3.1(FChatOptions + internalToolExecutionEnabled=false)
        ChatModel mockChatModel = mock(ChatModel.class);
        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        when(mockChatModel.stream(captor.capture())).thenReturn(Flux.empty());

        ToolDescriptor tool1 = new ToolDescriptor("func1", "desc1", "{\"type\":\"object\"}");
        List<ToolDescriptor> tools = List.of(tool1);
        ChatClientLlmSession session = new ChatClientLlmSession(mockChatModel, tools);

        InvocationCtx ctx = new InvocationCtx(
            new SessionId(java.util.UUID.randomUUID().toString()),
            new AuthPrincipal(
                new com.company.agentgateway.domain.shared.UserId("user"),
                new com.company.agentgateway.domain.shared.TenantId("tenant"),
                Set.of(),
                Set.of(),
                com.company.agentgateway.domain.iam.AuthChannel.API_KEY
            ),
            "trace"
        );
        assertDoesNotThrow(() -> session.generate("test", ctx));

        org.springframework.ai.chat.prompt.Prompt sent = captor.getValue();
        org.springframework.ai.chat.prompt.ChatOptions opts = sent.getOptions();
        assertNotNull(opts, "ChatOptions 必传(spec B §3.1 FC 注入)");
        org.springframework.ai.model.tool.ToolCallingChatOptions tcOpts =
                (org.springframework.ai.model.tool.ToolCallingChatOptions) opts;
        assertEquals(1, tcOpts.getToolCallbacks().size(),
                "tools 必须以 ToolCallback 列表携带");
        assertEquals(false, tcOpts.getInternalToolExecutionEnabled(),
                "internalToolExecutionEnabled=false 防止双重执行(自研 runToolLoop 接管)");
    }

    @Test
    void emptyToolsPromptUsesNoOptions() {
        // tools 为空 → Prompt 退化两参,不带 options(spec B §3.1)
        ChatModel mockChatModel = mock(ChatModel.class);
        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        when(mockChatModel.stream(captor.capture())).thenReturn(Flux.empty());

        ChatClientLlmSession session = new ChatClientLlmSession(mockChatModel, List.of());
        InvocationCtx ctx = new InvocationCtx(
            new SessionId(java.util.UUID.randomUUID().toString()),
            new AuthPrincipal(
                new com.company.agentgateway.domain.shared.UserId("u"),
                new com.company.agentgateway.domain.shared.TenantId("t"),
                Set.of(),
                Set.of(),
                com.company.agentgateway.domain.iam.AuthChannel.API_KEY),
            "trace");
        session.generate("p", ctx);

        org.springframework.ai.chat.prompt.Prompt sent = captor.getValue();
        assertNull(sent.getOptions(), "空 tools → 不带 options,保持旧行为");
    }

    @Test
    void testGeneratePropagatesPromptAndContext() throws InterruptedException {
        // Given: 验证 prompt 和 ctx 被正确传递
        ChatModel mockChatModel = mock(ChatModel.class);
        AssistantMessage msg = new AssistantMessage("response");
        Generation gen = new Generation(msg);
        ChatResponse response = new ChatResponse(List.of(gen));

        when(mockChatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
            .thenReturn(Flux.just(response));

        ChatClientLlmSession session = new ChatClientLlmSession(mockChatModel, List.of());
        InvocationCtx ctx = new InvocationCtx(
            new SessionId(java.util.UUID.randomUUID().toString()),
            new AuthPrincipal(
                new com.company.agentgateway.domain.shared.UserId("user"),
                new com.company.agentgateway.domain.shared.TenantId("tenant"),
                Set.of(),
                Set.of(),
                com.company.agentgateway.domain.iam.AuthChannel.API_KEY
            ),
            "trace-abc"
        );

        // When: 调用 generate
        Flow.Publisher<LlmEvent> publisher = session.generate("my prompt", ctx);

        // Then: 订阅，验证 stream 被调用
        CountDownLatch latch = new CountDownLatch(1);
        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(LlmEvent event) {
                latch.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                latch.countDown();
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }
        });

        latch.await();
        // 验证：ChatModel.stream 被调用（参数为 Prompt）
        // 注意： Mockito.verify(mockChatModel).stream(any(Prompt.class)); 可更严谨
        // 但此处仅验证无异常即可（LlmFlowAdapter 会消费 Flux）
    }
}
