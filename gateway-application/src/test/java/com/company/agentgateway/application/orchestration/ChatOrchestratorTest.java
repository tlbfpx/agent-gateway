package com.company.agentgateway.application.orchestration;

import com.company.agentgateway.domain.iam.AgentGrant;
import com.company.agentgateway.domain.iam.AuthChannel;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.Authenticator;
import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.orchestration.AgentCardPort;
import com.company.agentgateway.domain.orchestration.ChatClientPort;
import com.company.agentgateway.domain.orchestration.LlmEvent;
import com.company.agentgateway.domain.orchestration.LlmSession;
import com.company.agentgateway.domain.orchestration.SessionRepository;
import com.company.agentgateway.domain.orchestration.ToolDescriptor;
import com.company.agentgateway.domain.orchestration.ToolEvent;
import com.company.agentgateway.domain.orchestration.ToolPort;
import com.company.agentgateway.domain.registry.AgentCard;
import com.company.agentgateway.domain.session.Session;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.SessionId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatOrchestrator 测试：mock 所有 domain 端口，验证编排循环（含工具调用）。
 */
class ChatOrchestratorTest {

    private Authenticator authenticator;
    private SessionRepository sessionRepository;
    private AgentCardPort agentCardPort;
    private ChatClientPort chatClientPort;
    private ToolPort toolPort;
    private AuthorizationService authorizationService;
    private ChatOrchestrator orchestrator;

    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal(
            new UserId("u1"), new TenantId("t1"),
            Set.of(new AgentGrant("hr-agent", Set.of())),
            Set.of(new ModelId("qwen")),
            AuthChannel.API_KEY);

    @BeforeEach
    void setUp() {
        authenticator = mock(Authenticator.class);
        sessionRepository = mock(SessionRepository.class);
        agentCardPort = mock(AgentCardPort.class);
        chatClientPort = mock(ChatClientPort.class);
        toolPort = mock(ToolPort.class);
        authorizationService = mock(AuthorizationService.class);
        orchestrator = new ChatOrchestrator(authenticator, sessionRepository, agentCardPort,
                chatClientPort, toolPort, authorizationService,
                com.company.agentgateway.domain.observability.ObservabilityHooks.NOOP,
                new ModelId("qwen"));

        when(authenticator.authenticate("sk-test")).thenReturn(PRINCIPAL);
        when(authorizationService.canInvokeAgent(any(), any())).thenReturn(true);
        when(authorizationService.canUseModel(any(), any())).thenReturn(true);
    }

    private Session newSession() {
        return new Session(new SessionId("s1"), new TenantId("t1"), new UserId("u1"),
                new ModelId("qwen"), java.time.Instant.now(), java.time.Instant.now(), List.of());
    }

    private Flow.Publisher<LlmEvent> llmEvents(LlmEvent... events) {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                public void request(long n) {
                    for (LlmEvent e : events) {
                        subscriber.onNext(e);
                    }
                    subscriber.onComplete();
                }
                public void cancel() {}
            });
        };
    }

    private Flow.Publisher<ToolEvent> toolEvents(ToolEvent... events) {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                public void request(long n) {
                    for (ToolEvent e : events) {
                        subscriber.onNext(e);
                    }
                    subscriber.onComplete();
                }
                public void cancel() {}
            });
        };
    }

    /** 收集 orchestrator 的 Flux<ChatStreamEvent> 到队列（阻塞至完成）。 */
    private List<ChatStreamEvent> collect(ChatRequest request) throws Exception {
        java.util.Queue<ChatStreamEvent> received = new ConcurrentLinkedQueue<>();
        CountDownLatch done = new CountDownLatch(1);
        orchestrator.orchestrate(request, "sk-test").subscribe(e -> received.add(e),
                err -> done.countDown(),
                done::countDown);
        assertThat(done.await(10, TimeUnit.SECONDS)).as("orchestrate 未在超时内完成").isTrue();
        return List.copyOf(received);
    }

    @Test
    void 纯对话无工具_输出Delta然后Complete() throws Exception {
        Session s = newSession();
        when(sessionRepository.load(any())).thenReturn(s);
        when(chatClientPort.sessionFor(any(), any()))
                .thenReturn((prompt, history, ctx) -> llmEvents(
                        new LlmEvent.Delta("Hello"),
                        new LlmEvent.Delta(" world"),
                        new LlmEvent.Complete()));

        var events = collect(new ChatRequest(new SessionId("s1"), "hi", null));

        // 期望：2 个 Delta + 1 个 Complete（无 ToolCall）
        assertThat(events).filteredOn(e -> e instanceof ChatStreamEvent.Delta).hasSize(2);
        assertThat(events).filteredOn(e -> e instanceof ChatStreamEvent.Complete).hasSize(1);
        var complete = (ChatStreamEvent.Complete) events.stream()
                .filter(e -> e instanceof ChatStreamEvent.Complete).findFirst().orElseThrow();
        assertThat(complete.fullText()).isEqualTo("Hello world");
    }

    @Test
    void 工具调用_执行Agent后继续生成() throws Exception {
        Session session = newSession();
        when(sessionRepository.load(any())).thenReturn(session);
        AgentCard card = new AgentCard("hr-agent", "HR", List.of(), "{}", "{}", "1", true,
                "https://hr.example.com/a2a");
        when(agentCardPort.snapshot()).thenReturn(List.of(card));

        // 第一轮：LLM 决定调 hr-agent（无 delta，只 ToolCall）；第二轮需新 LlmSession 给最终回答
        AtomicInteger callCount = new AtomicInteger(0);
        when(chatClientPort.sessionFor(any(), any())).thenAnswer(inv -> {
            if (callCount.getAndIncrement() == 0) {
                // 第一轮：返回 ToolCall
                return (LlmSession) (prompt, history, ctx) -> llmEvents(
                        new LlmEvent.ToolCall("hr-agent", "{\"q\":\"请假\"}"));
            }
            // 第二轮：最终回答
            return (LlmSession) (prompt, history, ctx) -> llmEvents(
                    new LlmEvent.Delta("根据HR政策"),
                    new LlmEvent.Complete());
        });

        // 模拟工具结果（Delta 累积为结果）
        when(toolPort.invoke(any(), any(), any()))
                .thenReturn(toolEvents(new ToolEvent.Delta("可"), new ToolEvent.Delta("请假5天"),
                        new ToolEvent.Complete("")));

        var events = collect(new ChatRequest(new SessionId("s1"), "我能请假吗", null));

        // 期望：ToolCallStarted + ToolCallResult + Delta（第二轮）+ Complete
        assertThat(events).filteredOn(e -> e instanceof ChatStreamEvent.ToolCallStarted).hasSize(1);
        assertThat(events).filteredOn(e -> e instanceof ChatStreamEvent.ToolCallResult).hasSize(1);
        var tcResult = (ChatStreamEvent.ToolCallResult) events.stream()
                .filter(e -> e instanceof ChatStreamEvent.ToolCallResult).findFirst().orElseThrow();
        assertThat(tcResult.success()).isTrue();
    }

    @Test
    void 会话不存在抛错误事件() throws Exception {
        when(sessionRepository.load(any())).thenReturn(null);

        var events = collect(new ChatRequest(new SessionId("ghost"), "hi", null));

        assertThat(events).filteredOn(e -> e instanceof ChatStreamEvent.Error).hasSize(1);
        var err = (ChatStreamEvent.Error) events.stream()
                .filter(e -> e instanceof ChatStreamEvent.Error).findFirst().orElseThrow();
        assertThat(err.code()).isEqualTo("ORCHESTRATION_ERROR");
    }

    @Test
    void 新建会话_当sessionId为null时create() throws Exception {
        when(sessionRepository.create(any(), any(), any())).thenReturn(newSession());
        when(chatClientPort.sessionFor(any(), any()))
                .thenReturn((prompt, history, ctx) -> llmEvents(new LlmEvent.Delta("ok"), new LlmEvent.Complete()));

        var events = collect(new ChatRequest(null, "hi", null));

        assertThat(events).filteredOn(e -> e instanceof ChatStreamEvent.Complete).hasSize(1);
    }

    @Test
    void prompt为null时返回VALIDATION_ERROR而非NPE() throws Exception {
        var events = collect(new ChatRequest(null, null, null));

        assertThat(events).filteredOn(e -> e instanceof ChatStreamEvent.Error).hasSize(1);
        var err = (ChatStreamEvent.Error) events.stream()
                .filter(e -> e instanceof ChatStreamEvent.Error).findFirst().orElseThrow();
        assertThat(err.code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void prompt为空白时返回VALIDATION_ERROR() throws Exception {
        var events = collect(new ChatRequest(null, "   ", null));

        assertThat(events).filteredOn(e -> e instanceof ChatStreamEvent.Error).hasSize(1);
        var err = (ChatStreamEvent.Error) events.stream()
                .filter(e -> e instanceof ChatStreamEvent.Error).findFirst().orElseThrow();
        assertThat(err.code()).isEqualTo("VALIDATION_ERROR");
    }
}
