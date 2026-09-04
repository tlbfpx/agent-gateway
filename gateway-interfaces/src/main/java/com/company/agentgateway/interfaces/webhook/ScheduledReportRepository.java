package com.company.agentgateway.interfaces.webhook;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 定时报表订阅内存仓储（spec §25.4 一期不要求持久化，二期换 JPA 实现）。
 *
 * <p>{@link CopyOnWriteArrayList} 保证调度线程读、REST 线程写的并发安全：
 * 调度器每 60s 全量扫描（读多写少），写时复制的开销可忽略。
 */
public class ScheduledReportRepository {

    private final List<ScheduledReport> reports = new CopyOnWriteArrayList<>();

    /** 保存（同 reportId 覆盖，upsert 语义）。 */
    public ScheduledReport save(ScheduledReport report) {
        reports.removeIf(r -> r.reportId().equals(report.reportId()));
        reports.add(report);
        return report;
    }

    public Optional<ScheduledReport> findById(String reportId) {
        return reports.stream().filter(r -> r.reportId().equals(reportId)).findFirst();
    }

    /** 删除；返回是否命中。 */
    public boolean delete(String reportId) {
        return reports.removeIf(r -> r.reportId().equals(reportId));
    }

    /** 全量快照（调度器扫描用）。 */
    public List<ScheduledReport> findAll() {
        return List.copyOf(reports);
    }

    /**
     * 分页查询。
     *
     * @param tenant 租户过滤；{@code null} 或空表示不过滤
     * @param offset 起始下标（越界返回空列表）
     * @param limit  每页条数（≤ 0 视为不限制）
     */
    public List<ScheduledReport> list(String tenant, int offset, int limit) {
        List<ScheduledReport> filtered = reports.stream()
                .filter(r -> tenant == null || tenant.isBlank() || tenant.equals(r.tenant()))
                .toList();
        if (offset >= filtered.size() || offset < 0) return List.of();
        int end = limit <= 0 ? filtered.size() : Math.min(filtered.size(), offset + limit);
        return List.copyOf(filtered.subList(offset, end));
    }
}
