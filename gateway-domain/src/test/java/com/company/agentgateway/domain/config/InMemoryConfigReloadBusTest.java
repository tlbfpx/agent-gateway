package com.company.agentgateway.domain.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConfigReloadBus 行为契约测试(Sprint 1 P0)。
 *
 * <p>覆盖:订阅/发布/取消/通配/异常隔离/最近事件缓存。
 */
class InMemoryConfigReloadBusTest {

    private InMemoryConfigReloadBus bus;

    @BeforeEach
    void setUp() {
        bus = new InMemoryConfigReloadBus();
    }

    @Test
    @DisplayName("同名订阅者收到对应事件,异步派发不阻塞发布者")
    void publishToMatchingSubscriber() throws Exception {
        List<ConfigChanged> received = new ArrayList<>();
        bus.subscribe("models", received::add);

        ConfigChanged event = ConfigChanged.of("models", ConfigChanged.Source.FILE, 1L, "tester");
        long start = System.nanoTime();
        bus.publish(event);
        long publishMs = (System.nanoTime() - start) / 1_000_000;

        // 异步派发,等待短窗口
        waitFor(() -> !received.isEmpty(), 1000);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).name()).isEqualTo("models");
        assertThat(received.get(0).version()).isEqualTo(1L);
        // 异步派发意味着 publish 几乎不耗时(< 50ms 视为合理)
        assertThat(publishMs).isLessThan(50);
    }

    @Test
    @DisplayName("不同 name 的订阅者互不影响")
    void dispatchRoutesByName() throws Exception {
        List<ConfigChanged> modelsReceived = new ArrayList<>();
        List<ConfigChanged> webhooksReceived = new ArrayList<>();
        bus.subscribe("models", modelsReceived::add);
        bus.subscribe("webhook", webhooksReceived::add);

        bus.publish(ConfigChanged.of("models", ConfigChanged.Source.REST, 2L, "u1"));
        waitFor(() -> !modelsReceived.isEmpty(), 1000);

        assertThat(modelsReceived).hasSize(1);
        assertThat(webhooksReceived).isEmpty();
    }

    @Test
    @DisplayName("通配符订阅者收到所有事件")
    void wildcardSubscriber() throws Exception {
        List<ConfigChanged> all = new ArrayList<>();
        bus.subscribe("*", all::add);

        bus.publish(ConfigChanged.of("models", ConfigChanged.Source.REST, 1L, "u1"));
        bus.publish(ConfigChanged.of("webhook", ConfigChanged.Source.REST, 1L, "u1"));
        waitFor(() -> all.size() >= 2, 2000);

        assertThat(all).hasSizeGreaterThanOrEqualTo(2);
        assertThat(all).extracting(ConfigChanged::name)
                .contains("models", "webhook");
    }

    @Test
    @DisplayName("异常订阅者不影响其他订阅者与发布者")
    void subscriberExceptionsAreIsolated() throws Exception {
        AtomicInteger okCount = new AtomicInteger();
        bus.subscribe("models", e -> { throw new RuntimeException("boom"); });
        bus.subscribe("models", e -> okCount.incrementAndGet());

        bus.publish(ConfigChanged.of("models", ConfigChanged.Source.REST, 1L, "u1"));
        waitFor(() -> okCount.get() > 0, 1000);

        assertThat(okCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("unsubscribe 后不再收到事件")
    void unsubscribeStopsDelivery() throws Exception {
        List<ConfigChanged> received = new ArrayList<>();
        ConfigReloadBus.Subscription sub = bus.subscribe("models", received::add);

        bus.publish(ConfigChanged.of("models", ConfigChanged.Source.REST, 1L, "u1"));
        waitFor(() -> !received.isEmpty(), 1000);
        assertThat(received).hasSize(1);

        bus.unsubscribe(sub);
        bus.publish(ConfigChanged.of("models", ConfigChanged.Source.REST, 2L, "u1"));
        Thread.sleep(100); // 等异步派发结束
        assertThat(received).hasSize(1); // 仍是 1
    }

    @Test
    @DisplayName("lastEvent 返回最近事件,便于新订阅者立即同步")
    void lastEventProvidesLatest() {
        bus.publish(ConfigChanged.of("models", ConfigChanged.Source.REST, 1L, "u1"));
        bus.publish(ConfigChanged.of("models", ConfigChanged.Source.NACOS, 2L, "system"));

        ConfigChanged last = bus.lastEvent("models");
        assertThat(last).isNotNull();
        assertThat(last.source()).isEqualTo(ConfigChanged.Source.NACOS);
        assertThat(last.version()).isEqualTo(2L);
        assertThat(bus.lastEvent("unknown")).isNull();
    }

    @Test
    @DisplayName("同 name 上多个订阅者并行派发,均收到事件")
    void multipleSubscribersOnSameName() throws Exception {
        CountDownLatch latch = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            bus.subscribe("models", e -> latch.countDown());
        }
        bus.publish(ConfigChanged.of("models", ConfigChanged.Source.REST, 1L, "u1"));
        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("ConfigChanged 构造校验:name 不可为空")
    void rejectsBlankName() {
        try {
            new ConfigChanged(null, ConfigChanged.Source.FILE, 1L, null, null, "u", null);
            assertThat(false).as("should throw").isTrue();
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("name");
        }
    }

    @Test
    @DisplayName("ConfigChanged 默认 source 与 occurredAt")
    void defaultsAreApplied() {
        ConfigChanged e = new ConfigChanged("models", null, 1L, null, null, "u", null);
        assertThat(e.source()).isEqualTo(ConfigChanged.Source.FILE);
        assertThat(e.occurredAt()).isNotNull();
    }

    @Test
    @DisplayName("subscriberCounts 反映当前订阅分布(调试用)")
    void subscriberCountsReportsState() {
        bus.subscribe("models", e -> {});
        bus.subscribe("models", e -> {});
        bus.subscribe("webhook", e -> {});
        bus.subscribe("*", e -> {});

        assertThat(bus.subscriberCounts()).containsEntry("models", 2)
                .containsEntry("webhook", 1)
                .containsEntry("*", 1);
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }
}