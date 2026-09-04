package com.company.agentgateway.application.orchestration;

import com.company.agentgateway.application.billing.BudgetDowngradePolicy;
import com.company.agentgateway.domain.billing.Budget;
import com.company.agentgateway.domain.billing.BudgetRepository;
import com.company.agentgateway.domain.billing.BudgetType;
import com.company.agentgateway.domain.billing.InMemoryBudgetRepository;
import com.company.agentgateway.domain.iam.AgentGrant;
import com.company.agentgateway.domain.iam.AuthChannel;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.Authenticator;
import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.iam.RateLimiter;
import com.company.agentgateway.domain.orchestration.AgentCardPort;
import com.company.agentgateway.domain.orchestration.ChatClientPort;
import com.company.agentgateway.domain.orchestration.LlmEvent;
import com.company.agentgateway.domain.orchestration.LlmSession;
import com.company.agentgateway.domain.orchestration.SessionRepository;
import com.company.agentgateway.domain.orchestration.ToolPort;
import com.company.agentgateway.domain.session.Session;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.SessionId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 超限降级（P1）：Budget.overLimitAction=DOWNGRADE 时配额超限降级到 fallbackModel；
 * BLOCK（默认）维持 429 现状。
 */
class ChatOrchestratorDowngradeTest {

    private static final TenantId T1 = new TenantId("t1");
    private static final ModelId QWEN = new ModelId("qwen");
    private static final ModelId FALLBACK = new ModelId("qwen-turbo");

    private Authenticator authenticator;
    private SessionRepository sessionRepository;
    private AuthorizationService authorizationService;
    private ChatOrchestrator orchestrator;
    private BudgetRepository budgetRepository;

    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal(
            new UserId("u1"), T1, Set.of(new AgentGrant("a", Set.of())),
            Set.of(QWEN, FALLBACK), AuthChannel.API_KEY);

    @BeforeEach
    void setUp() {
        authenticator = mock(Authenticator.class);
        sessionRepository = mock(SessionRepository.class);
        authorizationService = mock(AuthorizationService.class);
        budgetRepository = new InMemoryBudgetRepository();
        // token 日预算永远拒绝（模拟超限）
        RateLimiter denying = mock(RateLimiter.class);
        when(denying.tryAcquire(any(), any(), any())).thenReturn(null);
        when(denying.tryAcquireTokens(any(), any(long.class))).thenReturn(false);

        orchestrator = new ChatOrchestrator(authenticator, sessionRepository,
                mock(AgentCardPort.class), chatPort(), mock(ToolPort.class),
                authorizationService, denying, null,
                com.company.agentgateway.domain.observability.ObservabilityHooks.NOOP,
                new LastNHistoryPolicy(40),
                com.company.agentgateway.domain.observability.OutputSanitizer.NOOP,
                QWEN);
        orchestrator.setBudgetDowngradePolicy(new BudgetDowngradePolicy(budgetRepository));

        when(authenticator.authenticate("sk-test")).thenReturn(PRINCIPAL);
        when(authorizationService.canInvokeAgent(any(), any())).thenReturn(true);
        when(sessionRepository.load(any())).thenReturn(new Session(
                new SessionId("s1"), T1, new UserId("u1"), QWEN,
                java.time.Instant.now(), java.time.Instant.now(), List.of()));
    }

    private ChatClientPort chatPort() {
        ChatClientPort port = mock(ChatClientPort.class);
        when(port.sessionFor(any(), any())).thenReturn(
                (LlmSession) (prompt, history, ctx) -> events(
                        new LlmEvent.Delta("ok"), new LlmEvent.Complete()));
        return port;
    }

    private Flow.Publisher<LlmEvent> events(LlmEvent... evts) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            public void request(long n) {
                for (LlmEvent e : evts) subscriber.onNext(e);
                subscriber.onComplete();
            }
            public void cancel() {}
        });
    }

    private void saveBudget(Budget.OverLimitAction action, String fallback) {
        budgetRepository.save(new Budget(T1, null, BudgetType.TOKEN,
                BigDecimal.TEN, BigDecimal.valueOf(100), BigDecimal.ZERO, BigDecimal.ZERO,
                new com.company.agentgateway.domain.billing.AlertThreshold(80),
                false, null, null, action, fallback));
    }

    private List<ChatStreamEvent> collect() throws Exception {
        java.util.Queue<ChatStreamEvent> received = new ConcurrentLinkedQueue<>();
        CountDownLatch done = new CountDownLatch(1);
        orchestrator.orchestrate(new ChatRequest(new SessionId("s1"), "hi", QWEN), "sk-test")
                .subscribe(received::add, err -> done.countDown(), done::countDown);
        assertThat(done.await(10, TimeUnit.SECONDS)).as("orchestrate 未在超时内完成").isTrue();
        return List.copyOf(received);
    }

    @Test
    void 默认BLOCK_配额超限仍429() throws Exception {
        saveBudget(Budget.OverLimitAction.BLOCK, null);
        var events = collect();
        var error = events.stream().filter(e -> e instanceof ChatStreamEvent.Error).findFirst();
        assertThat(error).isPresent();
        assertThat(((ChatStreamEvent.Error) error.get()).code()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void DOWNGRADE_配额超限降级到fallback模型() throws Exception {
        saveBudget(Budget.OverLimitAction.DOWNGRADE, "qwen-turbo");
        var events = collect();
        var complete = events.stream().filter(e -> e instanceof ChatStreamEvent.Complete).findFirst();
        assertThat(complete).as("应降级成功而非 429").isPresent();
        assertThat(((ChatStreamEvent.Complete) complete.get()).meta().model())
                .isEqualTo("qwen-turbo");
    }

    @Test
    void DOWNGRADE_原模型即fallback_仍429() throws Exception {
        saveBudget(Budget.OverLimitAction.DOWNGRADE, "qwen");
        var events = collect();
        var error = events.stream().filter(e -> e instanceof ChatStreamEvent.Error).findFirst();
        assertThat(error).isPresent();
        assertThat(((ChatStreamEvent.Error) error.get()).code()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void 无预算_维持429现状() throws Exception {
        var events = collect();
        var error = events.stream().filter(e -> e instanceof ChatStreamEvent.Error).findFirst();
        assertThat(error).isPresent();
        assertThat(((ChatStreamEvent.Error) error.get()).code()).isEqualTo("RATE_LIMITED");
    }
}
