package com.company.agentgateway.infra.llm.adapter;





import org.reactivestreams.FlowAdapters;
import org.springframework.ai.chat.model.ChatResponse;

import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Flux&lt;ChatResponse&gt; → Flow.Publisher&lt;LlmEvent&gt; 适配器。
 *
 * <p><b>流式 ToolCall 累积(spec B §3.3)</b>:多帧同 toolCallId 的 ToolCall 按 id 累积 arguments;
 * 流结束时一次性 flush 所有累积的 ToolCall。ChatOrchestrator.runToolLoop 单次 collect
 * 即可拿到完整参数,避免半截参数传 A2A 调用失败。
 *
 * <p><b>勘误修订记录：</b>
 * <ul>
 *   <li>弃手写 SubmissionPublisher,改用 reactive-streams 标准库 FlowAdapters(计划评审修订)</li>
 *   <li>B 阶段 1:流式 ToolCall 按 toolCallId 累积(AtomicReference 实现多订阅者隔离),流结束 flush</li>
 * </ul>
 */
public final class LlmFlowAdapter {

    private LlmFlowAdapter() {}

    public static java.util.concurrent.Flow.Publisher<com.company.agentgateway.domain.orchestration.LlmEvent>
            adapt(Flux<ChatResponse> chatFlux) {
        // 每订阅者独立的累积容器 —— AtomicReference 持有可变 map,直接修改
        Map<String, ToolCallBuffer> accum = new LinkedHashMap<>();
        AtomicReference<Map<String, ToolCallBuffer>> accumRef = new AtomicReference<>(accum);

        Flux<com.company.agentgateway.domain.orchestration.LlmEvent> streamed = chatFlux.flatMap(
                resp -> Flux.fromIterable(toLlmEvents(resp, accumRef.get())));

        // 流结束 flush:把所有累积的 ToolCall 转成事件
        Flux<com.company.agentgateway.domain.orchestration.LlmEvent> flushed = Flux.concat(
                streamed,
                Flux.defer(() -> drainFlush(accumRef)));

        return FlowAdapters.toFlowPublisher(flushed);
    }

    /**
     * 单帧 → 事件列表(可能空)。
     *
     * <p>规则:
     * <ol>
     *   <li>有 toolCalls → 累积(同 id 拼接 arguments);不立即发,等流结束</li>
     *   <li>有文本 → 发 Delta</li>
     *   <li>累积阶段只发 Delta 不发 ToolCall(避免半截参数)</li>
     * </ol>
     */
    static java.util.List<com.company.agentgateway.domain.orchestration.LlmEvent> toLlmEvents(
            ChatResponse resp, Map<String, ToolCallBuffer> accum) {
        java.util.List<com.company.agentgateway.domain.orchestration.LlmEvent> out = new java.util.ArrayList<>();
        if (resp == null) return out;
        var result = resp.getResult();
        if (result == null || result.getOutput() == null) return out;
        var output = result.getOutput();

        // 累积 toolCall(不立即发)
        var tcs = output.getToolCalls();
        if (tcs != null) {
            for (var tc : tcs) {
                String id = tc.id() == null ? tc.name() : tc.id();
                ToolCallBuffer buf = accum.computeIfAbsent(id, k -> new ToolCallBuffer(tc.name(), tc.id()));
                buf.appendArguments(tc.arguments());
            }
        }

        // 文本 → Delta(无文本发空 Delta 保持事件流连续;runToolLoop 用此判 Complete)
        var content = output.getText();
        out.add(new com.company.agentgateway.domain.orchestration.LlmEvent.Delta(content == null ? "" : content));
        return out;
    }

    /** 流结束 flush:原子取出累积的所有 ToolCall(单次)。 */
    static Flux<com.company.agentgateway.domain.orchestration.LlmEvent> drainFlush(
            AtomicReference<Map<String, ToolCallBuffer>> accumRef) {
        // 原子快照 → 副本构建 events(避免迭代时并发修改);build 后清空容器(防重发)
        Map<String, ToolCallBuffer> snapshot = accumRef.get();
        java.util.List<com.company.agentgateway.domain.orchestration.LlmEvent> out = new java.util.ArrayList<>();
        snapshot.values().forEach(buf -> out.add(buf.build()));
        snapshot.clear();  // flush 后清空,streamed 后续帧若继续抵达不会重复发
        return Flux.fromIterable(out);
    }

    /** 累积容器(spec C1 §3.3):name + arguments 拼接 + toolCallId(首帧 id)。 */
    static final class ToolCallBuffer {
        final String name;
        final String id;  // Spring AI ToolCall.id,首帧记录
        final StringBuilder args = new StringBuilder();
        ToolCallBuffer(String name) { this(name, null); }
        ToolCallBuffer(String name, String id) { this.name = name; this.id = id; }
        synchronized void appendArguments(String more) {
            if (more != null && !more.isBlank()) args.append(more);
        }
        com.company.agentgateway.domain.orchestration.LlmEvent.ToolCall build() {
            return new com.company.agentgateway.domain.orchestration.LlmEvent.ToolCall(name, args.toString(), id);
        }
    }
}