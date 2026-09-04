package com.company.agentgateway.domain.audit;

import com.company.agentgateway.domain.shared.TenantId;

import java.time.Instant;
import java.util.List;

/**
 * 出站端口：审计日志存储（spec §22）。由 infra 实现（InMemory 默认 / DB·ES 二期）。
 *
 * <p>原则：**append-only + 不可篡改**（spec §8.6）。记录 who→what→when→result。
 * 与 §7 trace 边界：trace 是性能/调用链（运维用），审计是合规追溯（安全用）。
 */
public interface AuditRepository {

    /** 追加审计日志（不可修改/删除，仅 append）。 */
    void append(AuditLog log);

    /** 查询审计日志（按时间范围/类型/操作者筛选）。 */
    List<AuditLog> query(TenantId tenant, AuditEventType type, Instant from, Instant to, int limit);

    /**
     * 查询审计日志（扩展过滤 + 分页）。
     *
     * <p>语义：先按全部条件过滤，再排序（默认新→旧），最后跳过 offset 条、取 limit 条。
     * keyword 对 actor / resourceId / errorMessage 做大小写不敏感包含匹配；null 表示不过滤。
     * 实现应在存储层完成过滤与分页（内存实现可在实现内过滤 + offset）。
     * 默认实现委托旧方法（忽略新增条件），仅为向后兼容；仓储实现应覆盖本方法。
     */
    default List<AuditLog> query(AuditQuery q) {
        return query(q.tenant(), q.type(), q.from(), q.to(), q.limit());
    }

    /** 审计查询条件（含 result / keyword / offset 分页）。 */
    record AuditQuery(TenantId tenant, AuditEventType type, Instant from, Instant to,
                      AuditLog.Result result, String keyword, int limit, int offset) {
        public AuditQuery {
            if (limit <= 0) limit = 50;
            if (offset < 0) offset = 0;
            if (keyword != null && keyword.isBlank()) keyword = null;
        }
    }

    /** 审计事件类型（spec §22.2）。 */
    enum AuditEventType {
        LOGIN, LOGOUT, API_KEY_CREATE, API_KEY_DELETE, AUTH_FAILED,
        GRANT_CREATE, GRANT_DELETE, RBAC_DENIED,
        AGENT_REGISTER, AGENT_DEREGISTER, MODEL_CONFIG_UPDATE,
        SESSION_CHAT, RATE_LIMIT_EXCEEDED
    }

    /** 审计日志记录（spec §22.3）。 */
    record AuditLog(String eventId, TenantId tenant, String actor, ActorType actorType,
                    AuditEventType eventType, Instant timestamp,
                    String resourceType, String resourceId, String action,
                    Result result, String errorMessage) {
        public enum ActorType { HUMAN, SERVICE, SYSTEM }
        public enum Result { SUCCESS, FAILURE }
    }
}
