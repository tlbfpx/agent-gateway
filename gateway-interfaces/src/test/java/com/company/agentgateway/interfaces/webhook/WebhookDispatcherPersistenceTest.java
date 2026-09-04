package com.company.agentgateway.interfaces.webhook;

import com.company.agentgateway.domain.config.ConfigChanged;
import com.company.agentgateway.domain.config.InMemoryConfigReloadBus;
import com.company.agentgateway.infra.config.JsonFileWebhookStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * WebhookDispatcher 与 JsonFileWebhookStore 集成测试(Sprint 1 P0 §3.4):
 * 验证 dispatcher 通过 store 持久化订阅,并能跟随外部文件修改热重载。
 */
class WebhookDispatcherPersistenceTest {

    @TempDir
    Path tempDir;

    private InMemoryConfigReloadBus bus;
    private JsonFileWebhookStore store;
    private WebhookDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        bus = new InMemoryConfigReloadBus();
        store = new JsonFileWebhookStore(tempDir.resolve("webhooks.json"), new ObjectMapper(), bus);
        store.start();
        dispatcher = new WebhookDispatcher();
        dispatcher.setStore(store);
    }

    @AfterEach
    void tearDown() {
        store.stop();
    }

    @Test
    @DisplayName("dispatcher.subscribe 把订阅同步落盘")
    void dispatcherSubscribePersistsToFile() throws Exception {
        dispatcher.subscribe(new WebhookDispatcher.Subscription(
                "https://hooks.example.com/a", "secret", List.of("order.*")));

        assertThat(Files.exists(tempDir.resolve("webhooks.json"))).isTrue();
        String content = Files.readString(tempDir.resolve("webhooks.json"));
        assertThat(content).contains("hooks.example.com").contains("secret");
        assertThat(dispatcher.listSubscriptions()).hasSize(1);
    }

    @Test
    @DisplayName("dispatcher.unsubscribe 同时移除内存 + 文件")
    void dispatcherUnsubscribeRemovesFromFile() throws Exception {
        dispatcher.subscribe(new WebhookDispatcher.Subscription(
                "https://a", "s", List.of("x")));
        dispatcher.subscribe(new WebhookDispatcher.Subscription(
                "https://b", "s", List.of("x")));
        assertThat(dispatcher.listSubscriptions()).hasSize(2);

        dispatcher.unsubscribe("https://a");

        assertThat(dispatcher.listSubscriptions()).hasSize(1);
        assertThat(store.list()).hasSize(1);
        assertThat(store.list().get(0).url()).isEqualTo("https://b");
    }

    @Test
    @DisplayName("外部修改 webhooks.json + 触发 bus 事件,dispatcher 自动 reload")
    void externalFileChangeReloadsDispatcher() throws Exception {
        // 启动时初始为空
        assertThat(dispatcher.listSubscriptions()).isEmpty();

        // 外部直接修改文件
        Files.writeString(tempDir.resolve("webhooks.json"), """
                [
                  {"url":"https://ext1","secret":"e1","events":["x"]},
                  {"url":"https://ext2","secret":"e2","events":["y"]}
                ]
                """);

        // 模拟 ConfigFileWatcher 发的事件
        bus.publish(new ConfigChanged(
                "webhooks", ConfigChanged.Source.FILE,
                System.currentTimeMillis(), null, null, "system", java.time.Instant.now()));

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(dispatcher.listSubscriptions()).hasSize(2));

        assertThat(dispatcher.listSubscriptions())
                .extracting(WebhookDispatcher.Subscription::url)
                .containsExactlyInAnyOrder("https://ext1", "https://ext2");
    }

    @Test
    @DisplayName("重启场景:已有 webhooks.json → 新 dispatcher 加载初始订阅")
    void restartLoadsExistingSubscriptions() throws Exception {
        // 第一轮:订阅 + 落盘
        dispatcher.subscribe(new WebhookDispatcher.Subscription(
                "https://persistent", "sec", List.of("alert.*")));
        assertThat(Files.exists(tempDir.resolve("webhooks.json"))).isTrue();

        // 第二轮:模拟重启 — 创建新 dispatcher
        store.stop();
        bus = new InMemoryConfigReloadBus();
        store = new JsonFileWebhookStore(tempDir.resolve("webhooks.json"), new ObjectMapper(), bus);
        store.start();
        WebhookDispatcher fresh = new WebhookDispatcher();
        fresh.setStore(store);

        assertThat(fresh.listSubscriptions()).hasSize(1);
        assertThat(fresh.listSubscriptions().get(0).url()).isEqualTo("https://persistent");
    }

    @Test
    @DisplayName("没有 store 的 dispatcher 仍按原行为工作(向后兼容)")
    void dispatcherWorksWithoutStore() {
        WebhookDispatcher bare = new WebhookDispatcher();
        bare.subscribe(new WebhookDispatcher.Subscription("https://x", "s", List.of("*")));
        assertThat(bare.listSubscriptions()).hasSize(1);
    }
}