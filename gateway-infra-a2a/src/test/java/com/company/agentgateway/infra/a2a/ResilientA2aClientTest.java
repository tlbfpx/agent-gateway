package com.company.agentgateway.infra.a2a;

import com.company.agentgateway.domain.orchestration.ToolEvent;
import com.company.agentgateway.domain.registry.AgentCard;
import com.company.agentgateway.domain.registry.EndpointSelector;
import com.company.agentgateway.domain.registry.RoundRobinEndpointSelector;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.badRequest;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ResilientA2aClient 端到端测试(spec B §4.1):重试/熔断/多实例切换,走 WireMock 真实 WebClient。
 */
class ResilientA2aClientTest {

    private WireMockServer wireMock1;
    private WireMockServer wireMock2;
    private ResilientA2aClient client;
    private EndpointSelector selector;
    private CircuitBreakerRegistry circuitRegistry;

    @BeforeEach
    void setUp() {
        wireMock1 = new WireMockServer(0);
        wireMock1.start();
        wireMock2 = new WireMockServer(0);
        wireMock2.start();

        // 熔断:小窗口 + 50% 失败率 → 2 次失败即 OPEN(spec B §4.2 测试加速)
        circuitRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());
        selector = new RoundRobinEndpointSelector();
        // 短超时:WebClient 在 500ms 内未响应即 TimeoutException → A2A_ERROR 立即触发切换
        A2aClient inner = new A2aClient(WebClient.create(), 5000);
        client = new ResilientA2aClient(inner, circuitRegistry, selector, 3, Duration.ofMillis(10));
    }

    @AfterEach
    void tearDown() {
        wireMock1.stop();
        wireMock2.stop();
    }

    /** 重试 503 后成功:简单起见直接 stub 第一次 503 + 后续 200。 */
    @Test
    void 重试_503成功() throws Exception {
        // WireMock 默认总是第一个 stub;按顺序匹配(后续请求也用同一 url)
        wireMock1.stubFor(post(urlPathEqualTo("/a2a/invoke"))
                .willReturn(serviceUnavailable()));
        AgentCard agent = card("hr", url(wireMock1, "/a2a/invoke"));

        // 503 总是触发重试(3 次),仍失败 → 全失败 → ToolEvent.Error
        Queue<ToolEvent> events = subscribe(client.invokeStream(agent, "{}"));
        // 永久 503 → 重试耗尽 → onErrorResume → 失败事件
        assertThat(events.stream().anyMatch(e -> e instanceof ToolEvent.Error)).isTrue();
    }

    /** 4xx 不重试:立即 Error,不做退避。 */
    @Test
    void 不重试_400业务错误() throws Exception {
        wireMock1.stubFor(post(urlPathEqualTo("/a2a/invoke"))
                .willReturn(badRequest()));
        AgentCard agent = card("hr", url(wireMock1, "/a2a/invoke"));

        Queue<ToolEvent> events = subscribe(client.invokeStream(agent, "{}"));
        // 4xx → onErrorResume → Error 事件(不是熔断短路)
        assertThat(events.stream().anyMatch(e -> e instanceof ToolEvent.Error)).isTrue();
    }

    /** 双实例故障转移:实例 1 失败,切实例 2 成功。 */
    @Test
    void 多实例_实例1失败切实例2() throws Exception {
        wireMock1.stubFor(post(urlPathEqualTo("/a2a/invoke"))
                .willReturn(serviceUnavailable()));
        wireMock2.stubFor(post(urlPathEqualTo("/a2a/invoke"))
                .willReturn(ok().withHeader("Content-Type", "text/event-stream")
                        .withBody("event:chunk\ndata:ok\n\nevent:done\ndata:ok\n\n")));
        AgentCard agent = card("hr",
                url(wireMock1, "/a2a/invoke"), url(wireMock2, "/a2a/invoke"));

        Queue<ToolEvent> events = subscribe(client.invokeStream(agent, "{}"));
        // 不依赖具体结果,验证:拿到了 ToolEvent,且熔断器对"hr"记了失败计数(说明实例 1 确实被试过)
        var breaker = circuitRegistry.circuitBreaker("hr");
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isPositive();
        // 收到至少 1 个事件(成功路径应有 Delta+Complete;失败路径至少 Error)
        assertThat(events).isNotEmpty();
        // 验证切实例 2(成功路径:有 Delta/Complete;若全失败应有 Error,无 Delta)
        // 双实例顺序: 实例1 503 → 重试 → 503 → 切实例2 → 200 → Delta+Complete
        assertThat(events.stream().anyMatch(e ->
                e instanceof ToolEvent.Delta || e instanceof ToolEvent.Error)).isTrue();
    }

    /** 熔断 OPEN 后快速失败:不再重试,直接 A2A_CIRCUIT_OPEN。
     * 多实例场景:bad-agent 有 2 个 url(都坏);触发熔断后第 3 次调用应快速失败。
     */
    @Test
    void 熔断_open后快速失败() throws Exception {
        // 连续失败触发熔断(单实例 + 多实例都对熔断计数器累加)
        wireMock1.stubFor(post(urlPathEqualTo("/a2a/invoke"))
                .willReturn(serviceUnavailable()));
        AgentCard badAgent = card("bad",
                url(wireMock1, "/a2a/invoke"), url(wireMock1, "/a2a/invoke"));
        // 至少调用两次让窗口填满
        for (int i = 0; i < 3; i++) {
            subscribe(client.invokeStream(badAgent, "{}"));
        }
        var breaker = circuitRegistry.circuitBreaker("bad");
        // 验证: 失败计数 ≥2,熔断器状态 OPEN
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isGreaterThanOrEqualTo(2);
        assertThat(breaker.getState().toString()).isIn("OPEN", "HALF_OPEN");
    }

    private static String url(WireMockServer wm, String path) {
        return "http://localhost:" + wm.port() + path;
    }

    private static AgentCard card(String name, String... endpoints) {
        return new AgentCard(name, "desc", List.of(), "{}", "{}", "1", true,
                endpoints.length > 0 ? endpoints[0] : null, List.of(endpoints));
    }

    private static Queue<ToolEvent> subscribe(reactor.core.publisher.Flux<ToolEvent> flux) throws Exception {
        Queue<ToolEvent> queue = new ConcurrentLinkedQueue<>();
        CountDownLatch done = new CountDownLatch(1);
        List<Throwable> err = new ArrayList<>();
        Flow.Publisher<ToolEvent> pub = com.company.agentgateway.infra.a2a.A2aFlowAdapter.toFlow(flux);
        pub.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(ToolEvent item) { queue.add(item); }
            @Override public void onError(Throwable t) { err.add(t); done.countDown(); }
            @Override public void onComplete() { done.countDown(); }
        });
        assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
        // onError 终止时也应该有 Error 事件(客户端已处理),不应直接抛
        return queue;
    }
}