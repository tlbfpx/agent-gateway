package com.company.agentgateway.domain.replay;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadCaptureHelperTest {

    private FakePort port;
    private PayloadCaptureHelper helper;

    @BeforeEach
    void setUp() {
        port = new FakePort();
        helper = new PayloadCaptureHelper(port);
    }

    @Test
    @DisplayName("captureRequest:把 prompt + model 写入 REQUEST payload")
    void captureRequest() {
        helper.captureRequest("trace-1", "你好世界", "gpt-4o");
        assertThat(port.records).hasSize(1);
        PayloadCapturePort.PayloadRecord r = port.records.get(0);
        assertThat(r.traceId()).isEqualTo("trace-1");
        assertThat(r.role()).isEqualTo(PayloadCapturePort.Role.REQUEST);
        assertThat(r.contentType()).isEqualTo("messages_json");
        assertThat(r.body()).contains("你好世界").contains("gpt-4o");
    }

    @Test
    @DisplayName("captureRequest:model=null 时输出 \"model\":null(JSON null)")
    void captureRequestNullModel() {
        helper.captureRequest("t", "hi", null);
        assertThat(port.records.get(0).body()).contains("\"model\":null");
    }

    @Test
    @DisplayName("captureResponse:把 tokens + text 落 RESPONSE payload,头部 metadata + 正文")
    void captureResponse() {
        helper.captureResponse("t", "回答内容", 100, 200);
        PayloadCapturePort.PayloadRecord r = port.records.get(0);
        assertThat(r.role()).isEqualTo(PayloadCapturePort.Role.RESPONSE);
        assertThat(r.body()).startsWith("{").contains("tokens_in\":100").contains("回答内容");
    }

    @Test
    @DisplayName("captureToolCall + captureToolResult:各自写入 1 条")
    void captureToolCallAndResult() {
        helper.captureToolCall("t", "span-1", "weather-agent", "{\"city\":\"Beijing\"}");
        helper.captureToolResult("t", "span-1", "weather-agent", "晴 25°C");

        assertThat(port.records).hasSize(2);
        assertThat(port.records.get(0).role()).isEqualTo(PayloadCapturePort.Role.TOOL_CALL);
        assertThat(port.records.get(0).body()).contains("weather-agent").contains("Beijing");
        assertThat(port.records.get(1).role()).isEqualTo(PayloadCapturePort.Role.TOOL_RESULT);
        assertThat(port.records.get(1).body()).contains("晴 25°C");
    }

    @Test
    @DisplayName("traceId=null 时不写入(防止污染)")
    void nullTraceIdSkipped() {
        helper.captureRequest(null, "x", "m");
        helper.captureToolCall(null, "s", "tool", "{}");
        helper.captureToolResult(null, "s", "tool", "r");
        helper.captureResponse(null, "x", 1, 1);
        assertThat(port.records).isEmpty();
    }

    @Test
    @DisplayName("中文 + emoji 转义安全")
    void specialCharsEscape() {
        helper.captureRequest("t", "引号\"和换行\n符", "gpt-4o");
        // body 中 \" 应被转义为 \"
        assertThat(port.records.get(0).body()).contains("\\\"").contains("\\n");
    }

    // ─── Fakes ───

    static class FakePort implements PayloadCapturePort {
        final List<PayloadRecord> records = new ArrayList<>();

        @Override public boolean capture(PayloadRecord record) { records.add(record); return true; }
        @Override public Optional<PayloadRecord> findByTraceAndRole(String t, Role r) { return Optional.empty(); }
        @Override public List<PayloadRecord> findByTrace(String t) { return List.of(); }
        @Override public int purgeBefore(Instant c) { return 0; }
    }
}