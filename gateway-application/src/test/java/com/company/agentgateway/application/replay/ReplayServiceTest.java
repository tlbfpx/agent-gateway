package com.company.agentgateway.application.replay;

import com.company.agentgateway.application.orchestration.ChatOrchestrator;
import com.company.agentgateway.application.orchestration.ChatRequest;
import com.company.agentgateway.application.orchestration.ChatStreamEvent;
import com.company.agentgateway.domain.replay.PayloadCapturePort;
import com.company.agentgateway.domain.replay.ReplayRequest;
import com.company.agentgateway.domain.replay.ReplayResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ReplayService 单元测试(Sprint 2 P0 §3.4)。
 * 使用 Fake PayloadCapturePort + mocked ChatOrchestrator。
 */
class ReplayServiceTest {

    private FakePayloadPort port;
    private ChatOrchestrator orch;
    private ReplayService service;

    @BeforeEach
    void setUp() {
        port = new FakePayloadPort();
        orch = mock(ChatOrchestrator.class);
        service = new ReplayService(port, orch, new ObjectMapper());
        when(orch.orchestrate(any(ChatRequest.class), any(String.class), any()))
                .thenReturn(Flux.just(
                        new ChatStreamEvent.Delta("hello "),
                        new ChatStreamEvent.Delta("world"),
                        new ChatStreamEvent.Complete("hello world",
                                new ChatStreamEvent.Meta("gpt-4o", 1, 2, false))));
    }

    @Test
    @DisplayName("default replay:同 payload → 字节级一致")
    void defaultReplayByteIdentical() {
        port.addPayload("trace-1", PayloadCapturePort.Role.REQUEST,
                "{\"prompt\":\"hi\",\"model\":\"gpt-4o\"}", Instant.now());
        port.addPayload("trace-1", PayloadCapturePort.Role.RESPONSE,
                "{}\n\nhello world", Instant.now());

        var result = service.replay(
                new ReplayRequest("trace-1", ReplayRequest.ReplayOverrides.empty(), true, false, null, null),
                "sk-test", null);

        assertThat(result.status()).isEqualTo(ReplayResult.Status.COMPLETED);
        assertThat(result.sourceTraceId()).isEqualTo("trace-1");
        assertThat(result.safeReplay()).isTrue();
        assertThat(result.metadata()).containsEntry("actualBytes", "hello world".length());
    }

    @Test
    @DisplayName("what-if replay:overrides.model 生效")
    void whatIfReplayOverridesModel() {
        port.addPayload("trace-1", PayloadCapturePort.Role.REQUEST,
                "{\"prompt\":\"hi\",\"model\":\"gpt-4o\"}", Instant.now());
        port.addPayload("trace-1", PayloadCapturePort.Role.RESPONSE,
                "{}\n\norig", Instant.now());

        var req = new ReplayRequest("trace-1",
                new ReplayRequest.ReplayOverrides("claude-3-opus", null, null, null, null, null, null),
                true, false, null, null);
        var result = service.replay(req, "sk", null);

        assertThat(result.status()).isEqualTo(ReplayResult.Status.COMPLETED);
        // Verify orchestrator was invoked with overridden model — via Mockito ArgumentCaptor
        var captor = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        org.mockito.Mockito.verify(orch).orchestrate(captor.capture(), any(String.class), any());
        assertThat(captor.getValue().model()).isNotNull();
        assertThat(captor.getValue().model().value()).isEqualTo("claude-3-opus");
    }

    @Test
    @DisplayName("缺 payload → 抛 IllegalStateException")
    void missingPayload() {
        assertThatThrownBy(() ->
                service.replay(new ReplayRequest("missing", ReplayRequest.ReplayOverrides.empty(), true, false, null, null), "sk", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No request payload");
    }

    @Test
    @DisplayName("orchestrator 抛异常 → 返回 FAILED status,带 errorMessage")
    void orchestratorFailure() {
        port.addPayload("trace-1", PayloadCapturePort.Role.REQUEST,
                "{\"prompt\":\"hi\"}", Instant.now());
        when(orch.orchestrate(any(ChatRequest.class), any(String.class), any()))
                .thenReturn(Flux.error(new RuntimeException("upstream 500")));

        var result = service.replay(new ReplayRequest("trace-1",
                ReplayRequest.ReplayOverrides.empty(), true, false, null, null), "sk", null);

        assertThat(result.status()).isEqualTo(ReplayResult.Status.FAILED);
        assertThat(result.errorMessage()).contains("upstream 500");
    }

    @Test
    @DisplayName("safeReplay 标志透传")
    void safeReplayFlagPassed() {
        port.addPayload("t", PayloadCapturePort.Role.REQUEST, "{\"prompt\":\"x\"}", Instant.now());
        var req = new ReplayRequest("t", ReplayRequest.ReplayOverrides.empty(), false, false, null, null);
        var result = service.replay(req, "sk", null);
        assertThat(result.safeReplay()).isFalse();
    }

    @Test
    @DisplayName("job 查询:replay 后可通过 jobId 查到结果")
    void jobQueryable() {
        port.addPayload("t", PayloadCapturePort.Role.REQUEST, "{\"prompt\":\"x\"}", Instant.now());
        var result = service.replay(new ReplayRequest("t",
                ReplayRequest.ReplayOverrides.empty(), true, false, null, null), "sk", null);
        var looked = service.job(result.jobId());
        assertThat(looked).isNotNull();
        assertThat(looked.status()).isEqualTo(ReplayResult.Status.COMPLETED);
    }

    @Test
    @DisplayName("replay 10 次同 trace:每次产生独立 jobId")
    void tenReplaysIndependent() {
        port.addPayload("t", PayloadCapturePort.Role.REQUEST, "{\"prompt\":\"x\"}", Instant.now());
        port.addPayload("t", PayloadCapturePort.Role.RESPONSE, "{}\n\nok", Instant.now());
        var ids = new ArrayList<String>();
        for (int i = 0; i < 10; i++) {
            var r = service.replay(new ReplayRequest("t",
                    ReplayRequest.ReplayOverrides.empty(), true, false, null, null), "sk", null);
            ids.add(r.jobId());
        }
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(service.recentJobs(10)).hasSize(10);
    }

    @Test
    @DisplayName("Sprint 2 P3:replay 完成后异步回写 2 条 payload(REQUEST + RESPONSE)")
    void replayWritesPayloads() throws Exception {
        port.addPayload("t", PayloadCapturePort.Role.REQUEST, "{\"prompt\":\"x\"}", Instant.now());
        port.addPayload("t", PayloadCapturePort.Role.RESPONSE,
                "{\"tokens_in\":10,\"tokens_out\":20}\n\nok", Instant.now());
        int before = port.records.size();

        var result = service.replay(new ReplayRequest("t",
                ReplayRequest.ReplayOverrides.empty(), true, false, null, null), "sk", null);
        // 等异步写完成
        Thread.sleep(200);

        // 至少应有 2 条新记录(REQUEST + RESPONSE,带 replayedFrom=true)
        assertThat(port.records.size()).isGreaterThanOrEqualTo(before + 2);
        boolean foundReplayReq = port.records.stream().anyMatch(r ->
                r.role() == PayloadCapturePort.Role.REQUEST
                        && r.traceId().equals(result.jobId())
                        && r.body().contains("replayedFrom"));
        boolean foundReplayResp = port.records.stream().anyMatch(r ->
                r.role() == PayloadCapturePort.Role.RESPONSE
                        && r.traceId().equals(result.jobId())
                        && r.body().contains("replayedFrom"));
        assertThat(foundReplayReq).isTrue();
        assertThat(foundReplayResp).isTrue();
    }

    @Test
    @DisplayName("Sprint 2 P5:async + callbackUrl → 立刻返回 PENDING,后台跑")
    void asyncReplay() throws Exception {
        port.addPayload("t", PayloadCapturePort.Role.REQUEST, "{\"prompt\":\"x\"}", Instant.now());
        port.addPayload("t", PayloadCapturePort.Role.RESPONSE, "{}\n\nok", Instant.now());

        var req = new ReplayRequest("t",
                ReplayRequest.ReplayOverrides.empty(), true, false,
                "http://localhost:1/cb", null); // 故意不可达 callback

        long start = System.currentTimeMillis();
        var result = service.replay(req, "sk", null);
        long elapsed = System.currentTimeMillis() - start;

        // 同步返回(应 < 200ms;不等 orchestrator 完成)
        assertThat(elapsed).isLessThan(500);
        assertThat(result.status()).isEqualTo(ReplayResult.Status.PENDING);

        // 等异步完成
        Thread.sleep(500);
        var polled = service.job(result.jobId());
        assertThat(polled).isNotNull();
        assertThat(polled.status()).isEqualTo(ReplayResult.Status.COMPLETED);
    }

    // ─── Fakes ───

    static class FakePayloadPort implements PayloadCapturePort {
        private final List<PayloadRecord> records = new ArrayList<>();

        void addPayload(String traceId, Role role, String body, Instant at) {
            records.add(new PayloadRecord(traceId, "span1", role, "json", body, body.length(), at));
        }

        @Override
        public boolean capture(PayloadRecord record) {
            records.add(record);
            return true;
        }

        @Override
        public Optional<PayloadRecord> findByTraceAndRole(String traceId, Role role) {
            return records.stream().filter(r -> r.traceId().equals(traceId) && r.role() == role).findFirst();
        }

        @Override
        public List<PayloadRecord> findByTrace(String traceId) {
            return records.stream().filter(r -> r.traceId().equals(traceId)).toList();
        }

        @Override
        public int purgeBefore(Instant cutoff) {
            int before = records.size();
            records.removeIf(r -> r.capturedAt().isBefore(cutoff));
            return before - records.size();
        }
    }

    static class FakeOrchestrator {} // placeholder no longer needed

    /** Placeholder — test no longer needs tracer. */
    @SuppressWarnings("unused")
    private static class TraceStub {}
}