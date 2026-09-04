package com.company.agentgateway.infra.observability.alert;

import com.company.agentgateway.domain.observability.AlertStore;
import com.company.agentgateway.domain.observability.AlertStore.AlertRecord;
import com.company.agentgateway.domain.observability.AlertStore.AlertRule;
import com.company.agentgateway.domain.observability.MetricPoint;
import com.company.agentgateway.domain.observability.MetricQueryRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AlertEngine 求值逻辑单测(spec 2026-08-19 §5.4):阈值命中/去重/静默/恢复。
 */
class AlertEngineTest {

    private final StubStore store = new StubStore();
    private final StubMetrics metrics = new StubMetrics();
    private final AlertEngine engine = new AlertEngine(store, metrics, 3600);

    private final AlertRule rule = new AlertRule("r-1", "错误过多", "chat.errors",
            AlertRule.Operator.GT, 5.0, 300, 30, "{rule}:{metric}", "critical", true,
            Instant.now(), Instant.now());

    @Test
    void 阈值命中_新告警firing() {
        store.saveRule(rule);
        metrics.windowValue = OptionalDouble.of(10.0);

        engine.evaluateRule(rule);

        assertThat(store.alerts).hasSize(1);
        AlertRecord a = store.alerts.get(0);
        assertThat(a.state()).isEqualTo("firing");
        assertThat(a.severity()).isEqualTo("critical");
        assertThat(a.triggerCount()).isEqualTo(1);
        assertThat(a.observedValue()).isEqualTo(10.0);
        assertThat(a.dedupKey()).isEqualTo("r-1:chat.errors");
    }

    @Test
    void 未达阈值_不触发() {
        metrics.windowValue = OptionalDouble.of(3.0);
        engine.evaluateRule(rule);
        assertThat(store.alerts).isEmpty();
    }

    @Test
    void 静默窗口内重复命中_只累计不新建() {
        store.saveRule(rule);
        metrics.windowValue = OptionalDouble.of(10.0);
        engine.evaluateRule(rule);
        engine.evaluateRule(rule);  // 30 分钟静默内第二次
        engine.evaluateRule(rule);  // 第三次

        assertThat(store.alerts).hasSize(1);
        assertThat(store.alerts.get(0).triggerCount()).isEqualTo(3);
    }

    @Test
    void 恢复_firing转resolved() {
        store.saveRule(rule);
        metrics.windowValue = OptionalDouble.of(10.0);
        engine.evaluateRule(rule);
        assertThat(store.alerts.get(0).state()).isEqualTo("firing");

        metrics.windowValue = OptionalDouble.of(1.0);
        engine.evaluateRule(rule);
        assertThat(store.alerts.get(0).state()).isEqualTo("resolved");
        assertThat(store.alerts.get(0).resolvedAt()).isNotNull();
    }

    @Test
    void 窗口无数据_不触发也不恢复() {
        store.saveRule(rule);
        metrics.windowValue = OptionalDouble.of(10.0);
        engine.evaluateRule(rule);

        metrics.windowValue = OptionalDouble.empty();
        engine.evaluateRule(rule);
        // 无数据保持 firing(不误恢复)
        assertThat(store.alerts.get(0).state()).isEqualTo("firing");
    }

    @Test
    void 已resolved再命中_新firing() {
        store.saveRule(rule);
        metrics.windowValue = OptionalDouble.of(10.0);
        engine.evaluateRule(rule);
        metrics.windowValue = OptionalDouble.of(1.0);
        engine.evaluateRule(rule);
        metrics.windowValue = OptionalDouble.of(8.0);
        engine.evaluateRule(rule);

        assertThat(store.alerts).hasSize(2);
        assertThat(store.alerts.get(1).state()).isEqualTo("firing");
    }

    @Test
    void 运算符语义() {
        AlertRule lt = new AlertRule("r-lt", "成功率跌", "success.rate",
                AlertRule.Operator.LT, 0.9, 300, 30, "{rule}:{metric}", "warning", true,
                Instant.now(), Instant.now());
        assertThat(lt.matches(0.85)).isTrue();
        assertThat(lt.matches(0.95)).isFalse();

        AlertRule gte = new AlertRule("r-gte", "至少", "m",
                AlertRule.Operator.GTE, 5.0, 300, 30, "{rule}:{metric}", "warning", true,
                Instant.now(), Instant.now());
        assertThat(gte.matches(5.0)).isTrue();
        assertThat(gte.matches(4.99)).isFalse();
    }

    // ================= 告警外呼通知(Webhook 推送) =================

    /** 记录事件的 GatewayEvents 桩。 */
    static class RecordingEvents implements com.company.agentgateway.domain.observability.GatewayEvents {
        final List<Map.Entry<String, Map<String, Object>>> published = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override
        public void publish(String eventType, Map<String, Object> payload) {
            published.add(Map.entry(eventType, payload));
        }
    }

    @Test
    void 新触发_firing推送ALERT_FIRED且payload正确() {
        RecordingEvents events = new RecordingEvents();
        AlertEngine e = new AlertEngine(store, metrics, events, 3600);
        store.saveRule(rule);
        metrics.windowValue = OptionalDouble.of(10.0);

        e.evaluateRule(rule);

        assertThat(store.alerts).hasSize(1);
        assertThat(events.published).hasSize(1);
        Map.Entry<String, Map<String, Object>> ev = events.published.get(0);
        assertThat(ev.getKey()).isEqualTo("ALERT_FIRED");
        Map<String, Object> payload = ev.getValue();
        assertThat(payload.get("alertId")).isEqualTo("al-0");
        assertThat(payload.get("ruleName")).isEqualTo("错误过多");
        assertThat(payload.get("metric")).isEqualTo("chat.errors");
        assertThat(payload.get("severity")).isEqualTo("critical");
        assertThat(payload.get("value")).isEqualTo(10.0);
        assertThat(payload.get("threshold")).isEqualTo(5.0);
        assertThat(payload.get("tenant")).isEqualTo("");
        assertThat((String) payload.get("time")).isNotBlank();
    }

    @Test
    void 静默窗口内重复命中_不重复推送() {
        RecordingEvents events = new RecordingEvents();
        AlertEngine e = new AlertEngine(store, metrics, events, 3600);
        store.saveRule(rule);
        metrics.windowValue = OptionalDouble.of(10.0);

        e.evaluateRule(rule);
        e.evaluateRule(rule);
        e.evaluateRule(rule);

        assertThat(store.alerts).hasSize(1);
        assertThat(store.alerts.get(0).triggerCount()).isEqualTo(3);
        assertThat(events.published).hasSize(1);  // 仅首次 firing 推送
    }

    @Test
    void 推送异常_不影响insertFiring落库与返回() {
        com.company.agentgateway.domain.observability.GatewayEvents boom = (t, p) -> {
            throw new IllegalStateException("webhook down");
        };
        AlertEngine e = new AlertEngine(store, metrics, boom, 3600);
        store.saveRule(rule);
        metrics.windowValue = OptionalDouble.of(10.0);

        e.evaluateRule(rule);  // 不应抛出

        assertThat(store.alerts).hasSize(1);
        assertThat(store.alerts.get(0).state()).isEqualTo("firing");
    }

    @Test
    void 恢复时推送ALERT_RESOLVED() {
        RecordingEvents events = new RecordingEvents();
        AlertEngine e = new AlertEngine(store, metrics, events, 3600);
        store.saveRule(rule);
        metrics.windowValue = OptionalDouble.of(10.0);
        e.evaluateRule(rule);

        metrics.windowValue = OptionalDouble.of(1.0);
        e.evaluateRule(rule);

        assertThat(events.published).hasSize(2);
        assertThat(events.published.get(1).getKey()).isEqualTo("ALERT_RESOLVED");
        assertThat(events.published.get(1).getValue().get("alertId")).isEqualTo("al-0");
        assertThat(events.published.get(1).getValue().get("severity")).isEqualTo("critical");
    }

    /** 内存 AlertStore 桩。 */
    static class StubStore implements AlertStore {
        final List<AlertRule> rules = new ArrayList<>();
        final List<AlertRecord> alerts = new ArrayList<>();

        @Override
        public AlertRule saveRule(AlertRule r) {
            rules.add(r);
            return r;
        }

        @Override
        public Optional<AlertRule> getRule(String id) {
            return rules.stream().filter(r -> r.id().equals(id)).findFirst();
        }

        @Override
        public List<AlertRule> listRules(boolean enabledOnly) {
            return rules.stream().filter(r -> !enabledOnly || r.enabled()).toList();
        }

        @Override
        public boolean deleteRule(String id) {
            return rules.removeIf(r -> r.id().equals(id));
        }

        @Override
        public AlertRecord insertFiring(AlertRecord alert) {
            AlertRecord saved = new AlertRecord("al-" + alerts.size(), alert.ruleId(),
                    alert.severity(), "firing", alert.dedupKey(), alert.labels(),
                    alert.firstFiredAt(), alert.recentlyTriggeredAt(), alert.triggerCount(),
                    alert.observedValue(), alert.threshold(), null, null, null);
            alerts.add(saved);
            return saved;
        }

        @Override
        public Optional<AlertRecord> findLatestByDedupKey(String dedupKey) {
            return alerts.stream()
                    .filter(a -> a.dedupKey().equals(dedupKey))
                    .reduce((first, second) -> second);
        }

        @Override
        public AlertRecord update(AlertRecord alert) {
            for (int i = 0; i < alerts.size(); i++) {
                if (alerts.get(i).id().equals(alert.id())) {
                    alerts.set(i, alert);
                    return alert;
                }
            }
            throw new IllegalArgumentException("not found");
        }

        @Override
        public List<AlertRecord> queryAlerts(String state, String severity, int limit) {
            return alerts.stream().limit(limit).toList();
        }

        @Override
        public Optional<AlertRecord> get(String id) {
            return alerts.stream().filter(a -> a.id().equals(id)).findFirst();
        }
    }

    /** 窗口值可设置的指标桩。 */
    static class StubMetrics implements MetricQueryRepository {
        OptionalDouble windowValue = OptionalDouble.empty();
        final AtomicReference<Double> lastWindowSumArg = new AtomicReference<>();

        @Override
        public List<MetricPoint> querySeries(String metricName, Map<String, String> tags,
                                              Instant from, Instant to) {
            return List.of();
        }

        @Override
        public OptionalDouble windowSum(String metricName, Map<String, String> tags,
                                         Instant from, Instant to) {
            return windowValue;
        }

        @Override
        public List<com.company.agentgateway.domain.observability.MetricQueryRepository.MetricBucket>
        queryBuckets(String metricName, Map<String, String> tags, Instant from, Instant to, int bucketSeconds) {
            return List.of();
        }
    }
}
