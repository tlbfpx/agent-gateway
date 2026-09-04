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
import com.company.agentgateway.domain.orchestration.PromptCachePort;
import com.company.agentgateway.domain.orchestration.SessionRepository;
import com.company.agentgateway.domain.orchestration.ToolPort;
import com.company.agentgateway.domain.registry.AgentCard;
import com.company.agentgateway.domain.session.Session;
import com.company.agentgateway.domain.session.UserMessage;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.SessionId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 提示缓存编排行为：命中/未命中/带工具不缓存/带 history 不缓存/流式命中单 chunk 包装/缓存键规范化。 */
class ChatOrchestratorPromptCacheTest {

    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal(
            new UserId("u1"), new TenantId("t1"),
            Set.of(new AgentGrant("hr-agent", Set.of())),
            Set.of(new ModelId("qwen")),
            AuthChannel.API_KEY);

    private Authenticator authenticator;
    private SessionRepository sessionRepository;
    private AgentCardPort agentCardPort;
    private ChatClientPort chatClientPort;
    private AuthorizationService authorizationService;
    private ChatOrchestrator orchestrator;
    private FakePromptCache cache;
    private final AtomicInteger llmCalls = new AtomicInteger();

    /** 轻量内存 fake（应用层测试不依赖 infra-llm 实现）。 */
    static class FakePromptCache implements PromptCachePort {
        final Map<String, CacheEntry> store = new HashMap<>();
        @Override public Optional<CacheEntry> get(String key) {
            return Optional.ofNullable(store.get(key));
    }
        @Override public void put(String key, CacheEntry entry) { store.put(key, entry); }
    }

    @BeforeEach
    void setUp() {
        authenticator = mock(Authenticator.class);
        sessionRepository = mock(SessionRepository.class);
        agentCardPort = mock(AgentCardPort.class);
        chatClientPort = mock(ChatClientPort.class);
        authorizationService = mock(AuthorizationService.class);
        mock(ToolPort.class);
        cache = new FakePromptCache();
        llmCalls.set(0);
        orchestrator = new ChatOrchestrator(authenticator, sessionRepository, agentCardPort,
                chatClientPort, mock(ToolPort.class), authorizationService,
                com.company.agentgateway.domain.observability.ObservabilityHooks.NOOP,
                new ModelId("qwen"));
        orchestrator.setPromptCache(cache);

        when(authenticator.authenticate("sk-test")).thenReturn(PRINCIPAL);
        when(authorizationService.canInvokeAgent(any(), any())).thenReturn(true);
        when(agentCardPort.snapshot()).thenReturn(List.of());
        when(chatClientPort.sessionFor(any(), any())).thenAnswer(inv -> {
            llmCalls.incrementAndGet();
            return (LlmSession) (prompt, history, ctx) -> llmPublisher(
                    new LlmEvent.Delta("cached-answer"),
                    new LlmEvent.Complete());
        });
    }

    private Session newSession(List<com.company.agentgateway.domain.session.Message> history) {
        return new Session(new SessionId("s1"), new TenantId("t1"), new UserId("u1"),
                new ModelId("qwen"), Instant.now(), Instant.now(), history);
    }

    private Flow.Publisher<LlmEvent> llmPublisher(LlmEvent... events) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            public void request(long n) {
                for (LlmEvent e : events) subscriber.onNext(e);
                subscriber.onComplete();
            }
            public void cancel() {}
        });
    }

    private List<ChatStreamEvent> collect(ChatRequest request) throws Exception {
        Queue<ChatStreamEvent> received = new ConcurrentLinkedQueue<>();
        CountDownLatch done = new CountDownLatch(1);
        orchestrator.orchestrate(request, "sk-test").subscribe(received::add,
                err -> done.countDown(), done::countDown);
        assertThat(done.await(10, TimeUnit.SECONDS)).as("orchestrate 未在超时内完成").isTrue();
        return List.copyOf(received);
    }

    private ChatStreamEvent.Complete lastComplete(List<ChatStreamEvent> events) {
        return (ChatStreamEvent.Complete) events.stream()
                .filter(e -> e instanceof ChatStreamEvent.Complete).findFirst().orElseThrow();
    }

    @Test
    void 首次未缓存_写入后第二次命中不再调LLM() throws Exception {
        when(sessionRepository.load(any())).thenReturn(newSession(List.of()));
        ChatRequest req = new ChatRequest(new SessionId("s1"), "hello", null);

        var first = collect(req);
        var c1 = lastComplete(first);
        assertThat(c1.fullText()).isEqualTo("cached-answer");
        assertThat(c1.meta().cacheHit()).isFalse();
        assertThat(llmCalls.get()).isEqualTo(1);
        assertThat(cache.store).hasSize(1);

        var second = collect(new ChatRequest(new SessionId("s2"), "hello", null));
        var c2 = lastComplete(second);
        assertThat(c2.meta().cacheHit()).isTrue();
        assertThat(c2.fullText()).isEqualTo("cached-answer");
        assertThat(llmCalls.get()).isEqualTo(1); // 命中，未再调 LLM
    }

    @Test
    void 命中时完整回答包装为单个Delta() throws Exception {
        when(sessionRepository.load(any())).thenReturn(newSession(List.of()));
        collect(new ChatRequest(new SessionId("s1"), "hello", null)); // 预热写入

        var events = collect(new ChatRequest(new SessionId("s2"), "hello", null));
        var deltas = events.stream().filter(e -> e instanceof ChatStreamEvent.Delta).toList();
        assertThat(deltas).hasSize(1);
        assertThat(((ChatStreamEvent.Delta) deltas.get(0)).content()).isEqualTo("cached-answer");
        assertThat(lastComplete(events).meta().cacheHit()).isTrue();
    }

    @Test
    void 缓存键规范化_trim与连续空白折叠后命中() throws Exception {
        when(sessionRepository.load(any())).thenReturn(newSession(List.of()));
        collect(new ChatRequest(new SessionId("s1"), "  hello   world\n\t again  ", null));

        // 排版不同（trim + 折叠空白）但语义相同 → 命中
        var events = collect(new ChatRequest(new SessionId("s2"), "hello world again", null));
        assertThat(lastComplete(events).meta().cacheHit()).isTrue();
    }

    @Test
    void 带工具的请求不缓存() throws Exception {
        when(sessionRepository.load(any())).thenReturn(newSession(List.of()));
        when(agentCardPort.snapshot()).thenReturn(List.of(new AgentCard(
                "hr-agent", "HR", List.of(), "{}", "{}", "1", true, "https://hr.example.com/a2a")));

        var events = collect(new ChatRequest(new SessionId("s1"), "hello", null));
        assertThat(lastComplete(events).meta().cacheHit()).isFalse();
        assertThat(cache.store).isEmpty(); // tools 非空不写缓存
        assertThat(llmCalls.get()).isEqualTo(1);
    }

    @Test
    void 带多轮history的会话不缓存() throws Exception {
        when(sessionRepository.load(any())).thenReturn(newSession(List.of(new UserMessage("earlier"))));

        var events = collect(new ChatRequest(new SessionId("s1"), "hello", null));
        assertThat(lastComplete(events).meta().cacheHit()).isFalse();
        assertThat(cache.store).isEmpty(); // 有 history 不写缓存
        assertThat(llmCalls.get()).isEqualTo(1);
    }

    @Test
    void 缓存键含模型ID_不同模型不命中() throws Exception {
        when(sessionRepository.load(any())).thenReturn(newSession(List.of()));
        collect(new ChatRequest(new SessionId("s1"), "hello", null));
        collect(new ChatRequest(new SessionId("s2"), "hello", new ModelId("qwen"))); // 同模型 → 命中
        assertThat(llmCalls.get()).isEqualTo(1);

        var events = collect(new ChatRequest(new SessionId("s3"), "hello", new ModelId("deepseek")));
        assertThat(lastComplete(events).meta().cacheHit()).isFalse();
        assertThat(llmCalls.get()).isEqualTo(2);
    }

    @Test
    void promptCacheKey为SHA256且跨规范化稳定() {
        String a = ChatOrchestrator.promptCacheKey(new ModelId("qwen"), "  hi   there ");
        String b = ChatOrchestrator.promptCacheKey(new ModelId("qwen"), "hi there");
        String c = ChatOrchestrator.promptCacheKey(new ModelId("qwen2"), "hi there");
        assertThat(a).isEqualTo(b).hasSize(64);
        assertThat(a).isNotEqualTo(c);
    }
}
