package com.company.agentgateway.interfaces.webhook;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * 定时报表订阅（spec §25.4）。
 *
 * <p>运营在成本中心「订阅」一个报表视图后，网关按 {@code period} 周期把 CSV 快照
 * 通过 Webhook 推给订阅方，替代此前只能手动下载一次 CSV 的做法。
 *
 * @param reportId    订阅 ID（全局唯一）
 * @param period      推送周期：{@code daily} / {@code weekly} / {@code monthly}
 * @param range       统计窗口：{@code 24h} / {@code 7d} / {@code 30d}
 * @param dim         聚合维度：{@code tenant} / {@code key} / {@code model} / {@code day}
 * @param webhookUrl  接收地址（须已在 {@link WebhookDispatcher} 注册订阅）
 * @param tenant      租户（租户隔离第一约束）
 * @param createdAt   创建时间
 * @param nextFireAt  下次触发时间（调度器扫描依据）
 */
public record ScheduledReport(
        String reportId,
        String period,
        String range,
        String dim,
        String webhookUrl,
        String tenant,
        Instant createdAt,
        Instant nextFireAt) {

    /** 合法周期白名单。 */
    public static final Set<String> PERIODS = Set.of("daily", "weekly", "monthly");

    /** 合法统计窗口白名单。 */
    public static final Set<String> RANGES = Set.of("24h", "7d", "30d");

    /** 合法聚合维度白名单。 */
    public static final Set<String> DIMS = Set.of("tenant", "key", "model", "day");

    public ScheduledReport {
        requireNonBlank(reportId, "reportId");
        requireNonBlank(webhookUrl, "webhookUrl");
        requireNonBlank(tenant, "tenant");
        if (period == null || !PERIODS.contains(period)) {
            throw new IllegalArgumentException("period must be one of " + PERIODS);
        }
        if (range == null || !RANGES.contains(range)) {
            throw new IllegalArgumentException("range must be one of " + RANGES);
        }
        if (dim == null || !DIMS.contains(dim)) {
            throw new IllegalArgumentException("dim must be one of " + DIMS);
        }
        if (createdAt == null) throw new IllegalArgumentException("createdAt must not be null");
        if (nextFireAt == null) throw new IllegalArgumentException("nextFireAt must not be null");
    }

    /** 周期步长：daily = 1d / weekly = 7d / monthly = 30d。 */
    public Duration periodStep() {
        return switch (period) {
            case "weekly" -> Duration.ofDays(7);
            case "monthly" -> Duration.ofDays(30);
            default -> Duration.ofDays(1);
        };
    }

    /** 统计窗口长度。 */
    public Duration rangeWindow() {
        return switch (range) {
            case "7d" -> Duration.ofDays(7);
            case "30d" -> Duration.ofDays(30);
            default -> Duration.ofHours(24);
        };
    }

    /** 推进到下一次触发时间（基于上次排期而非 now，避免漂移）。 */
    public ScheduledReport advanced() {
        return new ScheduledReport(reportId, period, range, dim, webhookUrl, tenant,
                createdAt, nextFireAt.plus(periodStep()));
    }

    /** Webhook 事件名：{@code cost.report.<period>}。 */
    public String eventType() {
        return "cost.report." + period;
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
