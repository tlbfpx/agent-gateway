package com.company.agentgateway.infra.persistence.audit;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.shared.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AuditRepository 内存实现（默认）。append-only 存储（仅 add，无修改/删除）。
 * 查询按 tenant + 可选 type + 时间范围筛选。
 */
public class InMemoryAuditRepository implements AuditRepository {

    private final CopyOnWriteArrayList<AuditLog> logs = new CopyOnWriteArrayList<>();

    @Override
    public void append(AuditLog log) {
        logs.add(log);
    }

    @Override
    public List<AuditLog> query(TenantId tenant, AuditEventType type, Instant from, Instant to, int limit) {
        return query(new AuditQuery(tenant, type, from, to, null, null, limit, 0));
    }

    /** 扩展查询：过滤（tenant/type/时间/result/keyword）后按时间新→旧排序，再 offset+limit 分页。 */
    @Override
    public List<AuditLog> query(AuditQuery q) {
        String kw = q.keyword() == null ? null : q.keyword().toLowerCase();
        return logs.stream()
                .filter(l -> l.tenant().equals(q.tenant()))
                .filter(l -> q.type() == null || l.eventType() == q.type())
                .filter(l -> q.from() == null || !l.timestamp().isBefore(q.from()))
                .filter(l -> q.to() == null || !l.timestamp().isAfter(q.to()))
                .filter(l -> q.result() == null || l.result() == q.result())
                .filter(l -> kw == null || containsIgnoreCase(l.actor(), kw)
                        || containsIgnoreCase(l.resourceId(), kw)
                        || containsIgnoreCase(l.errorMessage(), kw))
                .sorted(java.util.Comparator.comparing(AuditLog::timestamp).reversed())
                .skip(q.offset())
                .limit(q.limit())
                .toList();
    }

    private static boolean containsIgnoreCase(String value, String lowerCasedKeyword) {
        return value != null && value.toLowerCase().contains(lowerCasedKeyword);
    }
}
