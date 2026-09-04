package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.audit.AuditRepository.AuditEventType;
import com.company.agentgateway.domain.audit.AuditRepository.AuditLog;
import com.company.agentgateway.domain.audit.AuditRepository.AuditQuery;
import com.company.agentgateway.domain.audit.AuditRepository.AuditLog.ActorType;
import com.company.agentgateway.domain.audit.AuditRepository.AuditLog.Result;
import com.company.agentgateway.domain.shared.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AdminAuditController 扩展查询契约测试：result / keyword / offset 分页 / 组合查询 / 非法参数 400。
 */
class AdminAuditControllerTest {

    private static final TenantId T = new TenantId("au");

    private final StubAuditRepository repo = new StubAuditRepository();
    private AdminAuditController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminAuditController(repo);
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        repo.add(new AuditLog("e1", T, "alice@corp", ActorType.HUMAN, AuditEventType.LOGIN,
                base.plusSeconds(10), "session", "sess-1", "login", Result.SUCCESS, null));
        repo.add(new AuditLog("e2", T, "svc-billing", ActorType.SERVICE, AuditEventType.RBAC_DENIED,
                base.plusSeconds(20), "api", "res-invoice-9", "read", Result.FAILURE, "denied by policy"));
        repo.add(new AuditLog("e3", T, "bob@corp", ActorType.HUMAN, AuditEventType.SESSION_CHAT,
                base.plusSeconds(30), "session", "sess-2", "chat", Result.SUCCESS, "model timeout"));
        repo.add(new AuditLog("e4", T, "alice@corp", ActorType.HUMAN, AuditEventType.API_KEY_CREATE,
                base.plusSeconds(40), "apikey", "key-77", "create", Result.SUCCESS, null));
    }

    private List<Map<String, Object>> logs(String tenant, String type, String from, String to,
                                           String result, String keyword, int limit, int offset) {
        return controller.logs("test-key", tenant == null ? "au" : tenant, type, from, to, result, keyword, limit, offset);
    }

    @Test
    void keywordFiltersActorResourceAndErrorCaseInsensitively() {
        assertThat(logs(null, null, null, null, null, "ALICE", 50, 0))
                .extracting(m -> m.get("eventId")).containsExactly("e4", "e1");
        assertThat(logs(null, null, null, null, null, "invoice", 50, 0))
                .extracting(m -> m.get("eventId")).containsExactly("e2");
        assertThat(logs(null, null, null, null, null, "TIMEOUT", 50, 0))
                .extracting(m -> m.get("eventId")).containsExactly("e3");
        assertThat(logs(null, null, null, null, null, "no-such-keyword", 50, 0)).isEmpty();
    }

    @Test
    void resultFiltersCaseInsensitively() {
        assertThat(logs(null, null, null, null, "success", null, 50, 0))
                .extracting(m -> m.get("eventId")).containsExactly("e4", "e3", "e1");
        assertThat(logs(null, null, null, null, "FAILURE", null, 50, 0))
                .extracting(m -> m.get("eventId")).containsExactly("e2");
    }

    @Test
    void offsetPaginatesWithLimit() {
        List<Map<String, Object>> page1 = logs(null, null, null, null, null, null, 2, 0);
        List<Map<String, Object>> page2 = logs(null, null, null, null, null, null, 2, 2);
        List<Map<String, Object>> page3 = logs(null, null, null, null, null, null, 2, 4);
        assertThat(page1).extracting(m -> m.get("eventId")).containsExactly("e4", "e3");
        assertThat(page2).extracting(m -> m.get("eventId")).containsExactly("e2", "e1");
        assertThat(page3).isEmpty();
    }

    @Test
    void combinedFilters() {
        // result=success + keyword=alice + 分页
        assertThat(logs(null, null, null, null, "SUCCESS", "alice", 1, 1))
                .extracting(m -> m.get("eventId")).containsExactly("e1");
        // type + result
        assertThat(logs(null, "session_chat", null, null, "success", null, 50, 0))
                .extracting(m -> m.get("eventId")).containsExactly("e3");
        // 时间范围 + keyword
        assertThat(logs(null, null, "2026-01-01T00:00:15Z", "2026-01-01T00:00:45Z", null, "corp", 50, 0))
                .extracting(m -> m.get("eventId")).containsExactly("e4", "e3");
        // keyword 命中 errorMessage（denied by policy 在时间范围内）
        assertThat(logs(null, null, "2026-01-01T00:00:15Z", "2026-01-01T00:00:45Z", null, "billing", 50, 0))
                .extracting(m -> m.get("eventId")).containsExactly("e2");
    }

    @Test
    void invalidParametersReturn400() {
        assertThatThrownBy(() -> logs(null, null, null, null, "DENIED", null, 50, 0))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(400);
        assertThatThrownBy(() -> logs(null, "NOT_A_TYPE", null, null, null, null, 50, 0))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(400);
        assertThatThrownBy(() -> logs(null, null, "not-a-date", null, null, null, 50, 0))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(400);
        assertThatThrownBy(() -> logs(null, null, null, null, null, null, 50, -1))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(400);
        assertThatThrownBy(() -> logs(null, null, null, null, null, null, -5, 0))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(400);
    }

    /** 最小内存实现：覆盖 query(AuditQuery)，验证分页语义由仓储层承担。 */
    static class StubAuditRepository implements AuditRepository {
        private final List<AuditLog> logs = new ArrayList<>();

        void add(AuditLog log) {
            logs.add(log);
        }

        @Override
        public void append(AuditLog log) {
            logs.add(log);
        }

        @Override
        public List<AuditLog> query(TenantId tenant, AuditEventType type, Instant from, Instant to, int limit) {
            return query(new AuditQuery(tenant, type, from, to, null, null, limit, 0));
        }

        @Override
        public List<AuditLog> query(AuditQuery q) {
            String kw = q.keyword() == null ? null : q.keyword().toLowerCase(Locale.ROOT);
            return logs.stream()
                    .filter(l -> l.tenant().equals(q.tenant()))
                    .filter(l -> q.type() == null || l.eventType() == q.type())
                    .filter(l -> q.from() == null || !l.timestamp().isBefore(q.from()))
                    .filter(l -> q.to() == null || !l.timestamp().isAfter(q.to()))
                    .filter(l -> q.result() == null || l.result() == q.result())
                    .filter(l -> kw == null || contains(l.actor(), kw)
                            || contains(l.resourceId(), kw) || contains(l.errorMessage(), kw))
                    .sorted(java.util.Comparator.comparing(AuditLog::timestamp).reversed())
                    .skip(q.offset())
                    .limit(q.limit())
                    .toList();
        }

        private static boolean contains(String value, String kw) {
            return value != null && value.toLowerCase(Locale.ROOT).contains(kw);
        }
    }
}
