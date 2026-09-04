package com.company.agentgateway.infra.observability.alert;

import com.company.agentgateway.domain.observability.AlertStore;
import com.company.agentgateway.domain.observability.GatewayEvents;
import com.company.agentgateway.domain.observability.MetricQueryRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 站内告警引擎(spec 2026-08-19 §5.4):定时(30s)对 enabled 规则求值。
 *
 * <p>求值:规则窗口内 metrics_samples 的 sum 与阈值比较。
 * 命中 → dedup_key 聚合:已 firing 且在静默窗口内 → 只累计 count/时间;
 * 新命中 → 插入 firing。窗口内恢复正常 → firing 转 resolved(防抖:单轮即判)。
 */
public class AlertEngine {

    private static final Logger log = Logger.getLogger(AlertEngine.class.getName());

    /** 告警外呼事件类型。 */
    public static final String EVENT_ALERT_FIRED = "ALERT_FIRED";
    public static final String EVENT_ALERT_RESOLVED = "ALERT_RESOLVED";

    private final AlertStore alertStore;
    private final MetricQueryRepository metrics;
    private final GatewayEvents events;
    private final ScheduledExecutorService scheduler;

    public AlertEngine(AlertStore alertStore, MetricQueryRepository metrics, int intervalSeconds) {
        this(alertStore, metrics, GatewayEvents.NOOP, intervalSeconds);
    }

    public AlertEngine(AlertStore alertStore, MetricQueryRepository metrics,
                       GatewayEvents events, int intervalSeconds) {
        this.alertStore = alertStore;
        this.metrics = metrics;
        this.events = events == null ? GatewayEvents.NOOP : events;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "alert-engine");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleWithFixedDelay(this::evaluate,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    /** 单轮求值(对所有 enabled 规则)。失败跳过本轮(§7)。 */
    void evaluate() {
        try {
            for (AlertStore.AlertRule rule : alertStore.listRules(true)) {
                evaluateRule(rule);
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "告警求值失败(本轮跳过): {0}", e.getMessage());
        }
    }

    // 可见性:单规则求值逻辑被单测直接驱动
    void evaluateRule(AlertStore.AlertRule rule) {
        Instant now = Instant.now();
        Instant from = now.minus(Duration.ofSeconds(rule.windowSeconds()));
        OptionalDouble value = metrics.windowSum(rule.metricName(), Map.of(), from, now);
        if (value.isEmpty()) return;  // 窗口无数据:不触发也不恢复

        String dedupKey = dedupKey(rule);
        Optional<AlertStore.AlertRecord> existing = alertStore.findLatestByDedupKey(dedupKey);

        if (rule.matches(value.getAsDouble())) {
            trigger(rule, dedupKey, existing, value.getAsDouble(), now);
        } else {
            resolve(existing, rule, now);
        }
    }

    private void trigger(AlertStore.AlertRule rule, String dedupKey,
                         Optional<AlertStore.AlertRecord> existing,
                         double observed, Instant now) {
        if (existing.isPresent() && "firing".equals(existing.get().state())) {
            AlertStore.AlertRecord r = existing.get();
            // 静默窗口内:只累计,不新建(§5.4 去重)
            if (r.recentlyTriggeredAt().plus(Duration.ofMinutes(rule.silenceMinutes())).isAfter(now)) {
                alertStore.update(new AlertStore.AlertRecord(r.id(), r.ruleId(), r.severity(), "firing",
                        r.dedupKey(), r.labels(), r.firstFiredAt(), now,
                        r.triggerCount() + 1, observed, rule.threshold(),
                        r.claimedBy(), r.note(), null));
                return;
            }
        }
        // 新告警(或静默期后再次触发)
        AlertStore.AlertRecord saved = alertStore.insertFiring(new AlertStore.AlertRecord(
                null, rule.id(), rule.severity(), "firing", dedupKey,
                Map.of("rule", rule.name(), "metric", rule.metricName()),
                now, now, 1, observed, rule.threshold(), null, null, null));
        publishAlertEvent(EVENT_ALERT_FIRED, saved, rule, now);
    }

    private void resolve(Optional<AlertStore.AlertRecord> existing, AlertStore.AlertRule rule, Instant now) {
        if (existing.isEmpty()) return;
        AlertStore.AlertRecord r = existing.get();
        if (!"firing".equals(r.state())) return;
        AlertStore.AlertRecord resolved = alertStore.update(new AlertStore.AlertRecord(r.id(), r.ruleId(), r.severity(), "resolved",
                r.dedupKey(), r.labels(), r.firstFiredAt(), r.recentlyTriggeredAt(),
                r.triggerCount(), r.observedValue(), r.threshold(),
                r.claimedBy(), r.note(), now));
        publishAlertEvent(EVENT_ALERT_RESOLVED, resolved, rule, resolved.resolvedAt());
    }

    /** 推送告警外呼事件(异步、失败仅记日志,不影响告警落库)。 */
    private void publishAlertEvent(String eventType, AlertStore.AlertRecord alert,
                                   AlertStore.AlertRule rule, Instant time) {
        try {
            java.util.HashMap<String, Object> payload = new java.util.HashMap<>();
            payload.put("alertId", alert.id());
            payload.put("ruleName", rule != null ? rule.name() : alert.ruleId());
            payload.put("metric", rule != null ? rule.metricName() : alert.labels().get("metric"));
            payload.put("severity", alert.severity());
            payload.put("value", alert.observedValue());
            payload.put("threshold", alert.threshold());
            payload.put("tenant", alert.labels().getOrDefault("tenant", ""));
            payload.put("time", (time != null ? time : alert.recentlyTriggeredAt()).toString());
            events.publish(eventType, payload);
        } catch (Exception e) {
            log.log(Level.WARNING, "告警事件推送失败({0}, 不影响告警落库): {1}",
                    new Object[]{eventType, e.getMessage()});
        }
    }

    /** dedup_key 模板渲染:{rule}/{metric}/{tenant} 占位替换。 */
    static String dedupKey(AlertStore.AlertRule rule) {
        return rule.dedupKeyTpl()
                .replace("{rule}", rule.id())
                .replace("{metric}", rule.metricName())
                .replace("{tenant}", "");
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}
