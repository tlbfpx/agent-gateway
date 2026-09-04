package com.company.agentgateway.domain.quota;

import java.time.Duration;
import java.time.Instant;

/**
 * 配额决策（spec §16.2 + D2 GW-QUOTA-003）。
 *
 * <p>Java 21 sealed 强制 exhaustiveness（编译期检查 Pattern Matching 完备性）。
 *
 * <p>HTTP 映射（spec §GW-QUOTA-006）：
 * <ul>
 *   <li>{@link Allowed} → 放行（200）</li>
 *   <li>{@link Throttled} → 放行（应用节流配置）</li>
 *   <li>{@link Suspended} → 403 + GW-4305</li>
 *   <li>{@link Rejected} → 429 + GW-4304</li>
 * </ul>
 */
public sealed interface QuotaDecision
        permits QuotaDecision.Allowed, QuotaDecision.Throttled, QuotaDecision.Suspended, QuotaDecision.Rejected {

    /** 放行（剩余配额数）。 */
    record Allowed(long remaining) implements QuotaDecision {}

    /** 节流（基线 QPS 百分比 + 限速期）。 */
    record Throttled(int newQpsPercent, Duration duration) implements QuotaDecision {}

    /** 暂停（spec §21.4 SUSPEND 策略：拒绝所有请求直到 untilAt）。 */
    record Suspended(String reason, Instant untilAt) implements QuotaDecision {}

    /** 拒绝（quotaDimension 超 limit，used 当前用量）。 */
    record Rejected(String quotaDimension, long limit, long used) implements QuotaDecision {}
}
