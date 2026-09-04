package com.company.agentgateway.infra.a2a;

import com.company.agentgateway.domain.orchestration.ToolEvent;
import org.springframework.http.codec.ServerSentEvent;

/**
 * A2A SSE 事件 → domain ToolEvent 映射（无状态）。
 *
 * <p>A2A SSE 约定（spec §8.2）：
 * <ul>
 *   <li>event=chunk / data=delta 内容 → {@link ToolEvent.Delta}</li>
 *   <li>event=done / data=完整结果 → {@link ToolEvent.Complete}</li>
 *   <li>event=error / data=错误信息 → {@link ToolEvent.Error}</li>
 *   <li>无 event 字段（默认 data 行）→ 视为 delta</li>
 * </ul>
 */
public final class SseEventMapper {

    static final String EVENT_CHUNK = "chunk";
    static final String EVENT_DELTA = "delta";
    static final String EVENT_DONE = "done";
    static final String EVENT_COMPLETE = "complete";
    static final String EVENT_ERROR = "error";

    private SseEventMapper() {
    }

    /** ServerSentEvent&lt;String&gt; → ToolEvent。未知 event 视为 delta（容错）。 */
    public static ToolEvent toToolEvent(ServerSentEvent<String> sse) {
        String event = sse.event();
        String data = sse.data() == null ? "" : sse.data();

        if (EVENT_DONE.equals(event) || EVENT_COMPLETE.equals(event)) {
            return new ToolEvent.Complete(data);
        }
        if (EVENT_ERROR.equals(event)) {
            return new ToolEvent.Error("A2A_AGENT_ERROR", data);
        }
        // chunk / delta / null（默认）→ Delta
        return new ToolEvent.Delta(data);
    }
}
