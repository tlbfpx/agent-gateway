package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.observability.SpanQueryRepository;
import com.company.agentgateway.domain.observability.SpanQueryRepository.TraceFilter;
import com.company.agentgateway.domain.observability.SpanQueryRepository.TraceSummary;
import com.company.agentgateway.domain.observability.SpanRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AdminTraceController 单测(spec 2026-08-19 §5.3 契约)。
 * SpanQueryRepository 用内存桩(PG 实现由 persistence 集成测试覆盖)。
 */
class AdminTraceControllerTest {

    private StubSpanRepo repo;
    private AdminTraceController controller;

    @BeforeEach
    void setUp() {
        repo = new StubSpanRepo();
        controller = new AdminTraceController(repo);
    }

    @Test
    void listPassesFilters() {
        List<TraceSummary> traces = controller.list("1h", "gateway.chat", true, 100.0, "t1", 50, 0);
        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).traceId()).isEqualTo("tid-1");
        assertThat(repo.lastFilter.operation()).isEqualTo("gateway.chat");
        assertThat(repo.lastFilter.errorOnly()).isTrue();
        assertThat(repo.lastFilter.minDurationMs()).isEqualTo(100.0);
        assertThat(repo.lastFilter.tenantId()).isEqualTo("t1");
        assertThat(repo.lastLimit).isEqualTo(50);
    }

    @Test
    void detailReturnsSpans() {
        Map<String, Object> detail = controller.detail("tid-1");
        assertThat(detail.get("traceId")).isEqualTo("tid-1");
        assertThat((List<?>) detail.get("spans")).hasSize(2);
    }

    @Test
    void detailUnknownTraceThrows404() {
        assertThatThrownBy(() -> controller.detail("nope"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void noStorageReturns503WithGuide() {
        AdminTraceController bare = new AdminTraceController(null);
        assertThatThrownBy(() -> bare.list("1h", null, false, null, null, 50, 0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503")
                .hasMessageContaining("observability.storage");
    }

    /** 内存桩。 */
    static class StubSpanRepo implements SpanQueryRepository {
        TraceFilter lastFilter;
        int lastLimit;

        @Override
        public List<TraceSummary> queryTraces(TraceFilter filter, int limit, int offset) {
            lastFilter = filter;
            lastLimit = limit;
            return List.of(new TraceSummary("tid-1", "gateway.chat", Instant.now(),
                    1200.0, 4, 1, List.of("echo-agent")));
        }

        @Override
        public List<SpanRecord> getSpans(String traceId) {
            if (!"tid-1".equals(traceId)) return List.of();
            return List.of(
                    new SpanRecord("tid-1", "s1", null, "gateway.chat",
                            SpanRecord.Kind.SERVER, Instant.now(), Instant.now(), 1200.0,
                            SpanRecord.Status.OK, Map.of("tenant_id", "t1"), List.of()),
                    new SpanRecord("tid-1", "s2", "s1", "agent.call",
                            SpanRecord.Kind.CLIENT, Instant.now(), Instant.now(), 480.0,
                            SpanRecord.Status.ERROR, Map.of("agent_name", "echo-agent"), List.of()));
        }
    }
}
