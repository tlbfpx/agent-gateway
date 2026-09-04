package com.company.agentgateway.domain.observability;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 出站端口:告警规则与告警记录存储(spec 2026-08-19 §4.3/§5.4/§5.5)。由 infra-persistence 实现(PgAlertStore)。
 */
public interface AlertStore {

    // ================= 规则 CRUD =================

    AlertRule saveRule(AlertRule rule);

    Optional<AlertRule> getRule(String id);

    List<AlertRule> listRules(boolean enabledOnly);

    boolean deleteRule(String id);

    /**
     * 告警规则(§4.3)。
     *
     * @param dedupKeyTpl 去重键模板,占位符 {rule}/{metric}/{tenant} 运行时替换
     */
    record AlertRule(String id, String name, String metricName, Operator operator,
                     double threshold, int windowSeconds, int silenceMinutes,
                     String dedupKeyTpl, String severity, boolean enabled,
                     Instant createdAt, Instant updatedAt) {

        public enum Operator { GT, LT, GTE, LTE }

        /** 求值:窗口聚合值命中阈值返回 true。 */
        public boolean matches(double value) {
            return switch (operator) {
                case GT -> value > threshold;
                case LT -> value < threshold;
                case GTE -> value >= threshold;
                case LTE -> value <= threshold;
            };
        }
    }

    // ================= 告警记录 =================

    /** 新告警触发(firing)。 */
    AlertRecord insertFiring(AlertRecord alert);

    /** 按 dedup_key 查最近告警(state=firing 优先,时间倒序)。 */
    Optional<AlertRecord> findLatestByDedupKey(String dedupKey);

    /** 更新已有告警(触发计数/最近触发时间/状态流转/认领/静默)。 */
    AlertRecord update(AlertRecord alert);

    /** 告警流查询(state/severity 过滤,时间倒序,limit)。 */
    List<AlertRecord> queryAlerts(String state, String severity, int limit);

    Optional<AlertRecord> get(String id);

    /**
     * 告警记录(§4.3)。
     */
    record AlertRecord(String id, String ruleId, String severity, String state,
                       String dedupKey, Map<String, String> labels,
                       Instant firstFiredAt, Instant recentlyTriggeredAt,
                       int triggerCount, Double observedValue, Double threshold,
                       String claimedBy, String note, Instant resolvedAt) {}
}
