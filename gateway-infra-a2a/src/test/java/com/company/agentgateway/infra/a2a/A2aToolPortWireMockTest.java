package com.company.agentgateway.infra.a2a;

import com.company.agentgateway.domain.registry.AgentCard;
import com.company.agentgateway.domain.registry.EndpointSelector;
import com.company.agentgateway.domain.registry.RoundRobinEndpointSelector;
import com.company.agentgateway.domain.orchestration.InvocationCtx;
import com.company.agentgateway.domain.orchestration.ToolEvent;
import com.company.agentgateway.domain.orchestration.ToolPort;
import com.company.agentgateway.domain.iam.AuthChannel;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.SessionId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A2aToolPort 端到端 WireMock 测试：模拟 A2A SSE 响应，验证整条链路
 * （WebClient → ServerSentEvent 解码 → SseEventMapper → A2aFlowAdapter → Flow.Publisher → 订阅者）。
 */
class A2aToolPortWireMockTest {

    private WireMockServer wireMock;
    private ToolPort toolPort;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(0);
        wireMock.start();
        WebClient webClient = WebClient.builder().build();
        A2aClient a2aClient = new A2aClient(webClient, 5000);
        // 测试用 ResilientA2aClient:重试 1 次(避免 WireMock 偶发 1 失败让测试慢),窗口小,熔断宽
        CircuitBreakerRegistry cbr = CircuitBreakerRegistry.ofDefaults();
        EndpointSelector selector = new RoundRobinEndpointSelector();
        // 直接使用 inner A2aClient 包装 Resilient(避免 SSE 流超时问题在 flatMap 处)
        // 临时恢复:A2aToolPort 接受 ResilientA2aClient 才能用多实例/熔断
        ResilientA2aClient resilient = new ResilientA2aClient(a2aClient, cbr, selector, 2, java.time.Duration.ofMillis(10));
        toolPort = new A2aToolPort(resilient);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private InvocationCtx ctx() {
        return new InvocationCtx(new SessionId("s1"),
                new AuthPrincipal(new UserId("u1"), new TenantId("t1"),
                        java.util.Set.of(), java.util.Set.of(), AuthChannel.API_KEY),
                "trace-1");
    }

    private AgentCard agentAt(String path) {
        return new AgentCard("hr", "desc", List.of(), "{}", "{}", "1.0.0", true,
                "http://localhost:" + wireMock.port() + path);
    }

    @Test
    void 正常SSE流_映射为Delta然后Complete() throws Exception {
        // 模拟 A2A SSE 响应：2 个 chunk + 1 个 done
        wireMock.stubFor(post(urlPathEqualTo("/a2a/invoke"))
                .willReturn(ok()
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("" +
                                "event:chunk\ndata:Hello\n\n" +
                                "event:chunk\ndata: World\n\n" +
                                "event:done\ndata:Hello World\n\n")));

        java.util.Queue<ToolEvent> received = subscribeCollect(toolPort.invoke(agentAt("/a2a/invoke"), "{}", ctx()));

        assertThat(received).hasSize(3);
        assertThat(received.poll()).isInstanceOf(ToolEvent.Delta.class);
        assertThat(received.poll()).isInstanceOf(ToolEvent.Delta.class);
        ToolEvent last = received.poll();
        assertThat(last).isInstanceOf(ToolEvent.Complete.class);
        assertThat(((ToolEvent.Complete) last).fullResult()).isEqualTo("Hello World");
    }

    @Test
    void error事件_映射为ToolEventError() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/a2a/invoke"))
                .willReturn(ok()
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("event:error\ndata:agent boom\n\n")));

        java.util.Queue<ToolEvent> received = subscribeCollect(toolPort.invoke(agentAt("/a2a/invoke"), "{}", ctx()));

        assertThat(received).hasSize(1);
        ToolEvent e = received.poll();
        assertThat(e).isInstanceOf(ToolEvent.Error.class);
        assertThat(((ToolEvent.Error) e).message()).isEqualTo("agent boom");
    }

    @Test
    void endpointUrl缺失_直接返回Error不请求() throws Exception {
        AgentCard noUrl = new AgentCard("orphan", "d", List.of(), "{}", "{}", "0", true, null);

        java.util.Queue<ToolEvent> received = subscribeCollect(toolPort.invoke(noUrl, "{}", ctx()));

        assertThat(received).hasSize(1);
        ToolEvent e = received.poll();
        assertThat(e).isInstanceOf(ToolEvent.Error.class);
        assertThat(((ToolEvent.Error) e).code()).isEqualTo("A2A_NO_ENDPOINT");
        // 不应发起任何 HTTP 请求
        wireMock.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void 连接错误_转ToolEventError() throws Exception {
        // Agent 指向不存在的端口 → 连接拒绝
        AgentCard agent = new AgentCard("dead", "d", List.of(), "{}", "{}", "1", true,
                "http://localhost:1/a2a/invoke");

        java.util.Queue<ToolEvent> received = subscribeCollect(toolPort.invoke(agent, "{}", ctx()));

        assertThat(received).hasSize(1);
        assertThat(received.poll()).isInstanceOf(ToolEvent.Error.class);
    }

    /** 订阅 Flow.Publisher，收集所有事件直到 onComplete，返回队列。 */
    private java.util.Queue<ToolEvent> subscribeCollect(Flow.Publisher<ToolEvent> publisher) throws Exception {
        java.util.Queue<ToolEvent> received = new ConcurrentLinkedQueue<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(ToolEvent e) { received.add(e); }
            public void onError(Throwable t) { error.set(t); done.countDown(); }
            public void onComplete() { done.countDown(); }
        });
        assertThat(done.await(10, TimeUnit.SECONDS))
                .as("Flow 未在超时内完成").isTrue();
        if (error.get() != null) {
            throw new AssertionError("Flow.onError: " + error.get(), error.get());
        }
        return received;
    }
}
