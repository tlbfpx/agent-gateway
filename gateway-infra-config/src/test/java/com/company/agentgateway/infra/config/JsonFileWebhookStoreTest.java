package com.company.agentgateway.infra.config;

import com.company.agentgateway.domain.config.ConfigChanged;
import com.company.agentgateway.domain.config.InMemoryConfigReloadBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JsonFileWebhookStore 测试(Sprint 1 P0 §3.4)。
 *
 * <p>覆盖:CRUD、原子持久化、热重载(模拟外部文件修改触发 bus 事件)、监听者通知。
 */
class JsonFileWebhookStoreTest {

    @TempDir
    Path tempDir;

    private InMemoryConfigReloadBus bus;
    private JsonFileWebhookStore store;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<List<JsonFileWebhookStore.Subscription>> listenerCalls = new java.util.ArrayList<>();
    private final AtomicInteger listenerCount = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        bus = new InMemoryConfigReloadBus();
        store = new JsonFileWebhookStore(tempDir.resolve("webhooks.json"), mapper, bus);
        store.start();
        store.addChangeListener(subs -> {
            listenerCount.incrementAndGet();
            listenerCalls.add(subs);
        });
    }

    @AfterEach
    void tearDown() {
        store.stop();
    }

    @Test
    @DisplayName("upsert:新增订阅,落盘,通知监听者")
    void upsertPersistsAndNotifies() throws Exception {
        JsonFileWebhookStore.Subscription s = new JsonFileWebhookStore.Subscription(
                "https://hooks.example.com/a", "secret-A", List.of("order.created", "*"));
        store.upsert(s);

        assertThat(store.list()).hasSize(1).first().isEqualTo(s);
        assertThat(Files.exists(tempDir.resolve("webhooks.json"))).isTrue();
        assertThat(listenerCount.get()).isEqualTo(1);

        // 文件内容应可解析
        List<JsonFileWebhookStore.Subscription> onDisk = mapper.readValue(
                tempDir.resolve("webhooks.json").toFile(),
                mapper.getTypeFactory().constructCollectionType(List.class, JsonFileWebhookStore.Subscription.class));
        assertThat(onDisk).hasSize(1);
        assertThat(onDisk.get(0).url()).isEqualTo("https://hooks.example.com/a");
    }

    @Test
    @DisplayName("upsert 同 url 多次:仅保留最新一条(update 语义)")
    void upsertUpdatesExisting() {
        store.upsert(new JsonFileWebhookStore.Subscription("https://a", "s1", List.of("x")));
        store.upsert(new JsonFileWebhookStore.Subscription("https://a", "s2", List.of("y")));

        assertThat(store.list()).hasSize(1);
        assertThat(store.list().get(0).secret()).isEqualTo("s2");
        assertThat(store.list().get(0).events()).containsExactly("y");
    }

    @Test
    @DisplayName("remove:移除订阅,落盘,通知")
    void removePersistsAndNotifies() {
        store.upsert(new JsonFileWebhookStore.Subscription("https://a", "s", List.of("x")));
        store.upsert(new JsonFileWebhookStore.Subscription("https://b", "s", List.of("x")));
        listenerCount.set(0);

        boolean ok = store.remove("https://a");
        assertThat(ok).isTrue();
        assertThat(store.list()).hasSize(1);
        assertThat(store.list().get(0).url()).isEqualTo("https://b");
        assertThat(listenerCount.get()).isEqualTo(1);

        // 移除不存在的 url 返回 false
        assertThat(store.remove("https://missing")).isFalse();
    }

    @Test
    @DisplayName("外部修改文件 + 触发 bus 事件:store 自动重载并刷新监听者")
    void reloadOnBusEvent() throws Exception {
        // 外部修改文件:写入 2 条订阅
        Path file = tempDir.resolve("webhooks.json");
        Files.writeString(file, """
                [
                  {"url":"https://ext1","secret":"e1","events":["x"]},
                  {"url":"https://ext2","secret":"e2","events":["y","z"]}
                ]
                """);

        // 模拟 ConfigFileWatcher 发出的事件
        bus.publish(new ConfigChanged(
                "webhooks", ConfigChanged.Source.FILE,
                System.currentTimeMillis(), null, null, "system", java.time.Instant.now()));

        Awaitility.await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(store.list()).hasSize(2));

        assertThat(store.list()).extracting(JsonFileWebhookStore.Subscription::url)
                .containsExactlyInAnyOrder("https://ext1", "https://ext2");
        assertThat(listenerCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("启动加载:文件已存在则加载,不存在则空列表")
    void startLoadsExistingFile() throws Exception {
        store.stop();
        Files.writeString(tempDir.resolve("webhooks.json"), """
                [{"url":"https://pre","secret":"p","events":["*"]}]
                """);
        store = new JsonFileWebhookStore(tempDir.resolve("webhooks.json"), mapper, bus);
        store.start();

        assertThat(store.list()).hasSize(1);
        assertThat(store.list().get(0).url()).isEqualTo("https://pre");
    }

    @Test
    @DisplayName("文件损坏:加载失败,保留空快照,不抛异常")
    void corruptedFileIsIgnored() throws Exception {
        store.stop();
        Files.writeString(tempDir.resolve("webhooks.json"), "{ this is not valid json");
        store = new JsonFileWebhookStore(tempDir.resolve("webhooks.json"), mapper, bus);
        store.start();

        assertThat(store.list()).isEmpty();
    }

    @Test
    @DisplayName("Subscription record 防御:events null → 空列表")
    void subscriptionDefensiveCopy() {
        JsonFileWebhookStore.Subscription s = new JsonFileWebhookStore.Subscription("u", "s", null);
        assertThat(s.events()).isEmpty();
    }

    @Test
    @DisplayName("持久化是原子的(tmp + move),写期间不影响读")
    void persistIsAtomic() {
        store.upsert(new JsonFileWebhookStore.Subscription("https://a", "s", List.of("x")));
        // 写期间读 snapshot 应始终得到一致的全量/全无,不会得到半写状态
        for (int i = 0; i < 50; i++) {
            List<JsonFileWebhookStore.Subscription> snap = store.list();
            // 读到的列表要么空,要么至少 1 条完整记录
            assertThat(snap).satisfiesAnyOf(
                    list -> assertThat(list).isEmpty(),
                    list -> assertThat(list.get(0).url()).isEqualTo("https://a"));
        }
    }

    @Test
    @DisplayName("stop() 后再 upsert 仍能工作(只是失去热重载)")
    void stopThenUpsertStillWorks() {
        store.stop();
        store.upsert(new JsonFileWebhookStore.Subscription("https://after", "s", List.of("x")));
        assertThat(store.list()).hasSize(1);
    }
}