package com.company.agentgateway.infra.a2a;

import com.company.agentgateway.domain.orchestration.ToolEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import static org.assertj.core.api.Assertions.assertThat;

class SseEventMapperTest {

    private ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }

    @Test
    void chunk事件映射为Delta() {
        ToolEvent e = SseEventMapper.toToolEvent(sse("chunk", "hello"));
        assertThat(e).isInstanceOf(ToolEvent.Delta.class);
        assertThat(((ToolEvent.Delta) e).content()).isEqualTo("hello");
    }

    @Test
    void delta事件映射为Delta() {
        ToolEvent e = SseEventMapper.toToolEvent(sse("delta", "world"));
        assertThat(e).isInstanceOf(ToolEvent.Delta.class);
    }

    @Test
    void 无event默认视为Delta() {
        ToolEvent e = SseEventMapper.toToolEvent(ServerSentEvent.<String>builder().data("bare").build());
        assertThat(e).isInstanceOf(ToolEvent.Delta.class);
        assertThat(((ToolEvent.Delta) e).content()).isEqualTo("bare");
    }

    @Test
    void done事件映射为Complete() {
        ToolEvent e = SseEventMapper.toToolEvent(sse("done", "full result"));
        assertThat(e).isInstanceOf(ToolEvent.Complete.class);
        assertThat(((ToolEvent.Complete) e).fullResult()).isEqualTo("full result");
    }

    @Test
    void complete事件映射为Complete() {
        ToolEvent e = SseEventMapper.toToolEvent(sse("complete", "x"));
        assertThat(e).isInstanceOf(ToolEvent.Complete.class);
    }

    @Test
    void error事件映射为Error() {
        ToolEvent e = SseEventMapper.toToolEvent(sse("error", "boom"));
        assertThat(e).isInstanceOf(ToolEvent.Error.class);
        assertThat(((ToolEvent.Error) e).message()).isEqualTo("boom");
        assertThat(((ToolEvent.Error) e).code()).isEqualTo("A2A_AGENT_ERROR");
    }

    @Test
    void null_data当空串处理() {
        ToolEvent e = SseEventMapper.toToolEvent(sse("chunk", null));
        assertThat(((ToolEvent.Delta) e).content()).isEmpty();
    }
}
