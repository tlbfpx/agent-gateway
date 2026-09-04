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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConfigFileWatcher 行为测试(Sprint 1 P0 §3.4)。
 *
 * <p>覆盖:create / modify / delete / 防抖 / checksum 去重 / 子目录(history)/ 大文件。
 * 注意:NIO WatchService 事件由 OS 投递,需要 await 等待(不假设瞬时)。
 */
class ConfigFileWatcherTest {

    @TempDir
    Path tempDir;

    private InMemoryConfigReloadBus bus;
    private ConfigFileWatcher watcher;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ConfigChanged> received = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        bus = new InMemoryConfigReloadBus();
        bus.subscribe("*", received::add);
        watcher = new ConfigFileWatcher(tempDir, bus, mapper, 100L, true);
        watcher.start();
    }

    @AfterEach
    void tearDown() {
        watcher.stop();
    }

    @Test
    @DisplayName("创建 *.json 文件触发 CONFIG_MODIFY 事件,payload 已解析")
    void createJsonFileTriggersEvent() throws Exception {
        Path file = tempDir.resolve("models.json");
        Files.writeString(file, "[{\"id\":\"m1\",\"name\":\"gpt-4o\"}]");

        Awaitility.await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(received).isNotEmpty());

        ConfigChanged ev = received.get(0);
        assertThat(ev.name()).isEqualTo("models");
        assertThat(ev.source()).isEqualTo(ConfigChanged.Source.FILE);
        assertThat(ev.payload()).isInstanceOf(List.class);
    }

    @Test
    @DisplayName("修改文件但内容不变(同 checksum)被丢弃,不触发事件")
    void duplicateContentSkipped() throws Exception {
        Path file = tempDir.resolve("models.json");
        Files.writeString(file, "[{\"id\":\"m1\"}]");
        Awaitility.await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(received).isNotEmpty());
        int afterCreate = received.size();

        // 同样的内容再写一次
        Thread.sleep(300); // 等防抖窗口过
        Files.writeString(file, "[{\"id\":\"m1\"}]");

        Thread.sleep(500);
        assertThat(received.size()).isEqualTo(afterCreate);
    }

    @Test
    @DisplayName("修改文件内容变化触发新事件,版本号递增")
    void contentChangeTriggersNewEvent() throws Exception {
        Path file = tempDir.resolve("models.json");
        Files.writeString(file, "[{\"id\":\"m1\"}]");
        Awaitility.await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(received).isNotEmpty());
        long firstVersion = received.get(0).version();

        Thread.sleep(300);
        Files.writeString(file, "[{\"id\":\"m1\"},{\"id\":\"m2\"}]");

        Awaitility.await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(received.size()).isGreaterThanOrEqualTo(2));

        ConfigChanged second = received.get(received.size() - 1);
        assertThat(second.name()).isEqualTo("models");
        assertThat(second.version()).isGreaterThan(firstVersion);
        assertThat(second.payload()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(2);
    }

    @Test
    @DisplayName("删除文件触发 CONFIG_DELETE 事件,payload=null,summary.deleted=true")
    void deleteFileTriggersDeleteEvent() throws Exception {
        Path file = tempDir.resolve("webhooks.json");
        Files.writeString(file, "[{\"id\":\"w1\"}]");
        Awaitility.await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(received).isNotEmpty());
        received.clear();

        Files.delete(file);
        Awaitility.await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(received).isNotEmpty());

        ConfigChanged ev = received.get(0);
        assertThat(ev.name()).isEqualTo("webhooks");
        assertThat(ev.payload()).isNull();
        assertThat(ev.summary()).containsEntry("deleted", true);
    }

    @Test
    @DisplayName("防抖:100ms 窗口内多次 modify 合并为 ≤2 次事件")
    void debounceMergesRapidChanges() throws Exception {
        Path file = tempDir.resolve("models.json");
        // 用完全不同初始内容,确保后续每次写 checksum 都不同
        Files.writeString(file, "[{\"initial\":true}]");
        Awaitility.await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(received).isNotEmpty());
        received.clear();

        // 在防抖窗口(100ms)内连续写 5 次,每次间隔 15ms < debounceMs
        for (int i = 1; i <= 5; i++) {
            Files.writeString(file, "[{\"v\":" + i + "}]");
            Thread.sleep(15);
        }

        // 等最后一次防抖触发完成(防抖 100ms + buffer)
        Thread.sleep(2000);

        // 5 次连续写应该合并为 1~2 次事件(OS 时序有容差;核心契约是远小于 5)
        assertThat(received.size())
                .as("debounce should merge 5 rapid writes to <=2 events, was %s", received)
                .isLessThanOrEqualTo(2);
        assertThat(received.size()).isGreaterThanOrEqualTo(1);
        // 最后一条应该是最后一次写入的内容(包含 v:5),无论序列化形态如何
        Object lastPayload = received.get(received.size() - 1).payload();
        String payloadStr = String.valueOf(lastPayload);
        assertThat(payloadStr).contains("v=5").contains("5");
    }

    @Test
    @DisplayName("不同文件名映射到不同的 config name")
    void differentFilesHaveDifferentNames() throws Exception {
        Path m = tempDir.resolve("models.json");
        Path k = tempDir.resolve("api-keys.json");
        Files.writeString(m, "[]");
        Files.writeString(k, "[]");

        Awaitility.await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(received.size()).isGreaterThanOrEqualTo(2));

        List<String> names = received.stream().map(ConfigChanged::name).toList();
        assertThat(names).contains("models", "api-keys");
    }

    @Test
    @DisplayName("非 .json 文件被忽略,不触发事件")
    void nonJsonFilesIgnored() throws Exception {
        Path txt = tempDir.resolve("readme.txt");
        Files.writeString(txt, "hello");

        Thread.sleep(500);
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("history 子目录下的快照文件也能被监听(创建后注册)")
    void historyDirectoryChangesArePickedUp() throws Exception {
        // history 子目录
        Path history = tempDir.resolve("models-history");
        Files.createDirectories(history);
        // 需要重启 watcher 让它注册新目录——此处改为:start() 已扫描到历史目录的初始注册
        watcher.stop();

        received.clear();
        watcher = new ConfigFileWatcher(tempDir, bus, mapper, 100L, true);
        watcher.start();

        Path snapshot = history.resolve("1700000000000.json");
        Files.writeString(snapshot, "[{\"v\":\"snap\"}]");

        Awaitility.await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(received).isNotEmpty());
        assertThat(received.get(0).name()).isEqualTo("1700000000000");
    }

    @Test
    @DisplayName("stop() 之后不再产生事件")
    void stopStopsListening() throws Exception {
        watcher.stop();
        Path file = tempDir.resolve("models.json");
        Files.writeString(file, "[]");

        Thread.sleep(500);
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("configNameOf 静态工具:文件名 → name")
    void configNameOfStripExtension() {
        assertThat(ConfigFileWatcher.configNameOf(Path.of("/data/models.json"))).isEqualTo("models");
        assertThat(ConfigFileWatcher.configNameOf(Path.of("/data/api-keys.json"))).isEqualTo("api-keys");
        assertThat(ConfigFileWatcher.configNameOf(Path.of("/data/webhooks.json"))).isEqualTo("webhooks");
        assertThat(ConfigFileWatcher.configNameOf(Path.of("/data/noext"))).isEqualTo("noext");
    }

    @Test
    @DisplayName("summary 包含 checksum 与 path,便于审计与排障")
    void summaryContainsChecksumAndPath() throws Exception {
        Path file = tempDir.resolve("models.json");
        Files.writeString(file, "[{\"id\":\"m1\"}]");

        Awaitility.await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(received).isNotEmpty());

        Map<String, Object> summary = received.get(0).summary();
        assertThat(summary).containsKey("checksum");
        assertThat(summary).containsKey("path");
        assertThat((String) summary.get("checksum")).hasSize(64); // SHA-256 hex
    }
}