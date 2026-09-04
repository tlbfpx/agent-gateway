package com.company.agentgateway.interfaces.webhook;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookDispatcherTest {

    @Test
    void 订阅与列表管理() {
        var d = new WebhookDispatcher();
        d.subscribe(new WebhookDispatcher.Subscription("http://x", "s", List.of("agent.invoked")));
        d.subscribe(new WebhookDispatcher.Subscription("http://y", "s", List.of("*")));
        assertThat(d.listSubscriptions()).hasSize(2);
        d.unsubscribe("http://x");
        assertThat(d.listSubscriptions()).hasSize(1);
    }

    @Test
    void 发布到不可达地址进入死信() throws Exception {
        var d = new WebhookDispatcher();
        d.subscribe(new WebhookDispatcher.Subscription("http://localhost:1/broken", "sec", List.of("*")));
        d.publish("test.event", Map.of("k", "v"));
        for (int i = 0; i < 90 && d.deadLetters().isEmpty(); i++) {
            Thread.sleep(500);
        }
        assertThat(d.deadLetters()).hasSize(1);
        assertThat(d.deadLetters().get(0).event()).isEqualTo("test.event");
        assertThat(d.deadLetters().get(0).attempts()).isEqualTo(5);
        // 死信保留原始 payload，供重新投递复用
        assertThat(d.deadLetters().get(0).payload()).containsEntry("k", "v");
    }

    @Test
    void 重新投递成功后从死信队列移除() throws Exception {
        var hits = new AtomicInteger();
        HttpServer server = startServer(200, hits);
        try {
            var d = new WebhookDispatcher();
            String url = "http://localhost:" + server.getAddress().getPort() + "/hook";
            d.subscribe(new WebhookDispatcher.Subscription(url, "sec", List.of("*")));
            var dl = new WebhookDispatcher.DeadLetter(url, "cost.report.daily", 5,
                    "exhausted retries", Map.of("csv", "a,b"));

            assertThat(d.redeliver(dl)).isTrue();
            assertThat(hits.get()).isEqualTo(1);
            assertThat(d.deadLetters()).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 重新投递成功移除已存在的同键死信() throws Exception {
        var hits = new AtomicInteger();
        HttpServer server = startServer(200, hits);
        try {
            var d = new WebhookDispatcher();
            String url = "http://localhost:" + server.getAddress().getPort() + "/hook";
            d.subscribe(new WebhookDispatcher.Subscription(url, "sec", List.of("*")));
            var dl = new WebhookDispatcher.DeadLetter(url, "e1", 5, "boom", Map.of("k", "v"));
            d.recordDeadLetter(dl);
            d.recordDeadLetter(new WebhookDispatcher.DeadLetter(url, "e2", 5, "boom", Map.of()));
            assertThat(d.deadLetters()).hasSize(2);

            assertThat(d.redeliver(dl)).isTrue();
            // 只移除 url+event 匹配的那条，另一条保留
            assertThat(d.deadLetters()).hasSize(1);
            assertThat(d.deadLetters().get(0).event()).isEqualTo("e2");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 重新投递失败保留在死信队列() throws Exception {
        var d = new WebhookDispatcher();
        String url = "http://localhost:1/broken";
        d.subscribe(new WebhookDispatcher.Subscription(url, "sec", List.of("*")));
        var dl = new WebhookDispatcher.DeadLetter(url, "e1", 5, "boom", Map.of("k", "v"));
        d.recordDeadLetter(dl);

        assertThat(d.redeliver(dl)).isFalse();
        // 失败：仍在队列中且未重复（url+event 去重）
        assertThat(d.deadLetters()).hasSize(1);
        assertThat(d.deadLetters().get(0).event()).isEqualTo("e1");
    }

    @Test
    void 重新投递未知订阅返回false() {
        var d = new WebhookDispatcher();
        var dl = new WebhookDispatcher.DeadLetter("http://not-subscribed", "e1", 5, "boom", Map.of());
        d.recordDeadLetter(dl);
        assertThat(d.redeliver(dl)).isFalse();
        assertThat(d.deadLetters()).hasSize(1);
    }

    @Test
    void 死信记录兼容无payload的旧构造() {
        var dl = new WebhookDispatcher.DeadLetter("http://x", "e", 5, "err");
        assertThat(dl.payload()).isEmpty();
    }

    private static HttpServer startServer(int status, AtomicInteger hits) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/hook", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return server;
    }
}
