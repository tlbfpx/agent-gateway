package com.company.agentgateway.infra.llm.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.company.agentgateway.domain.orchestration.LlmEvent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

/**
 * LlmFlowAdapter 测试(spec B §3.3:流式 ToolCall 累积语义)。
 *
 * <p>toLlmEvents(resp, accum) 单帧映射:Delta 即发,ToolCall 累积,流结束由 drainFlush 整体 flush。
 */
class LlmFlowAdapterTest {

    /** 单帧映射:Delta 内容直接发 */
    @Test
    void toLlmEvents_shouldEmitDelta_whenContentPresent() {
        ChatResponse resp = mockChatResponseWithContent("hello");
        Map<String, LlmFlowAdapter.ToolCallBuffer> accum = new LinkedHashMap<>();
        List<LlmEvent> events = LlmFlowAdapter.toLlmEvents(resp, accum);

        assertEquals(1, events.size());
        assertInstanceOf(LlmEvent.Delta.class, events.get(0));
        assertEquals("hello", ((LlmEvent.Delta) events.get(0)).content());
        assertTrue(accum.isEmpty(), "无 toolCall 不应累积");
    }

    /** ToolCall 累积而非立即发;同 id 拼接 arguments */
    @Test
    void toLlmEvents_shouldAccumulateToolCall_notEmitDirectly() {
        ToolCall tc = new ToolCall("call-123", "function", "search", "{\"query\":\"wea");
        ChatResponse resp = mockChatResponseWithToolCall(tc);
        Map<String, LlmFlowAdapter.ToolCallBuffer> accum = new LinkedHashMap<>();

        List<LlmEvent> events = LlmFlowAdapter.toLlmEvents(resp, accum);

        // 累积阶段:无 ToolCall 事件直接发出(避免半截参数)
        assertTrue(events.stream().noneMatch(e -> e instanceof LlmEvent.ToolCall));
        assertEquals(1, accum.size(), "应累积一条 ToolCall");
        assertEquals("search", accum.get("call-123").name);
    }

    /** 多帧同 toolCallId → 累积 arguments;流结束 flush 出完整 ToolCall */
    @Test
    void adapt_shouldAccumulateAndFlushOnComplete() throws InterruptedException {
        // 帧 1: args="{\"query\":\"wea"
        ToolCall tc1 = new ToolCall("call-123", "function", "search", "{\"query\":\"wea");
        ChatResponse frame1 = mockChatResponseWithToolCall(tc1);
        // 帧 2: 同 id, 续 "ther\"}"
        ToolCall tc2 = new ToolCall("call-123", "function", "search", "ther\"}");
        ChatResponse frame2 = mockChatResponseWithToolCall(tc2);
        // 帧 3: 文本收尾
        ChatResponse frame3 = mockChatResponseWithContent("done");

        Flow.Publisher<LlmEvent> pub = LlmFlowAdapter.adapt(Flux.just(frame1, frame2, frame3));
        List<LlmEvent> collected = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        pub.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(LlmEvent e) { collected.add(e); }
            @Override public void onError(Throwable t) { fail(t); }
            @Override public void onComplete() { done.countDown(); }
        });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        // ToolCall:3 帧累积同 id,流结束 flush 恰好 1 个完整 ToolCall(同 id 已拼接)
        long toolCalls = collected.stream().filter(e -> e instanceof LlmEvent.ToolCall).count();
        assertEquals(1, toolCalls, "同 id 累积:3 帧累积后 flush 一个完整 ToolCall");
        // Delta:每帧各 1 个(累积阶段也发 Delta 保持流连续)
        long deltas = collected.stream().filter(e -> e instanceof LlmEvent.Delta).count();
        assertEquals(3, deltas);
        LlmEvent.ToolCall finalTc = (LlmEvent.ToolCall) collected.stream()
                .filter(e -> e instanceof LlmEvent.ToolCall).findFirst().orElseThrow();
        assertEquals("search", finalTc.toolName());
        assertEquals("{\"query\":\"weather\"}", finalTc.argsJson());
    }

    /** finishReason 不再单独映射 Complete(content 优先);流结束仍发累积的 ToolCall */
    @Test
    void toLlmEvents_shouldPreferContentOverFinishReason() {
        ChatResponse resp = mockChatResponseWithFinishReason("STOP");
        Map<String, LlmFlowAdapter.ToolCallBuffer> accum = new LinkedHashMap<>();
        List<LlmEvent> events = LlmFlowAdapter.toLlmEvents(resp, accum);
        assertEquals(1, events.size());
        assertInstanceOf(LlmEvent.Delta.class, events.get(0));  // content "" → Delta
    }

    /** still running 不算 Complete —— 现状映射为 Delta */
    @Test
    void toLlmEvents_shouldMapStillRunningAsDelta() {
        ChatResponse resp = mockChatResponseWithFinishReason("STILL_RUNNING");
        Map<String, LlmFlowAdapter.ToolCallBuffer> accum = new LinkedHashMap<>();
        List<LlmEvent> events = LlmFlowAdapter.toLlmEvents(resp, accum);
        assertEquals(1, events.size());
        assertInstanceOf(LlmEvent.Delta.class, events.get(0));
    }

    /** 空内容返回空 Delta */
    @Test
    void toLlmEvents_shouldReturnEmptyDelta_whenNoContentNoToolCall() {
        ChatResponse resp = mockChatResponseWithContent(null);
        Map<String, LlmFlowAdapter.ToolCallBuffer> accum = new LinkedHashMap<>();
        List<LlmEvent> events = LlmFlowAdapter.toLlmEvents(resp, accum);
        assertEquals(1, events.size());
        assertEquals("", ((LlmEvent.Delta) events.get(0)).content());
    }

    /** 端到端序列:Deltas 顺序 + 无 ToolCall */
    @Test
    void adapt_shouldEmitEvents_sequence() throws InterruptedException {
        ChatResponse d1 = mockChatResponseWithContent("hello");
        ChatResponse d2 = mockChatResponseWithContent(" world");
        ChatResponse d3 = mockChatResponseWithContent("!");
        Flow.Publisher<LlmEvent> pub = LlmFlowAdapter.adapt(Flux.just(d1, d2, d3));
        List<LlmEvent> events = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        pub.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(LlmEvent e) { events.add(e); }
            @Override public void onError(Throwable t) { fail(t); }
            @Override public void onComplete() { done.countDown(); }
        });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(3, events.size());
        assertEquals("hello", ((LlmEvent.Delta) events.get(0)).content());
        assertEquals(" world", ((LlmEvent.Delta) events.get(1)).content());
        assertEquals("!", ((LlmEvent.Delta) events.get(2)).content());
    }

    /** 背压透传 */
    @Test
    void adapt_shouldRespectBackpressure() throws InterruptedException {
        ChatResponse d1 = mockChatResponseWithContent("a");
        ChatResponse d2 = mockChatResponseWithContent("b");
        ChatResponse d3 = mockChatResponseWithContent("c");
        Flow.Publisher<LlmEvent> pub = LlmFlowAdapter.adapt(Flux.just(d1, d2, d3));
        List<LlmEvent> events = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Flow.Subscription> subRef = new AtomicReference();

        pub.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { subRef.set(s); s.request(1); }
            @Override public void onNext(LlmEvent e) {
                events.add(e);
                if (events.size() == 1) subRef.get().request(2);
            }
            @Override public void onError(Throwable t) { fail(t); }
            @Override public void onComplete() { done.countDown(); }
        });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(3, events.size());
    }

    /** Cancel 透传到上游 Flux */
    @Test
    void adapt_shouldPropagateCancel() throws InterruptedException {
        AtomicInteger cancelCount = new AtomicInteger();
        ChatResponse d = mockChatResponseWithContent("x");
        Flux<ChatResponse> flux = Flux.just(d).doOnCancel(cancelCount::incrementAndGet);
        Flow.Publisher<LlmEvent> pub = LlmFlowAdapter.adapt(flux);
        CountDownLatch sub = new CountDownLatch(1);

        pub.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { sub.countDown(); s.cancel(); }
            @Override public void onNext(LlmEvent e) { fail("不应收到事件"); }
            @Override public void onError(Throwable t) { fail(t); }
            @Override public void onComplete() { fail("不应完成"); }
        });

        assertTrue(sub.await(5, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertEquals(1, cancelCount.get());
    }

    /** Error 透传 */
    @Test
    void adapt_shouldPropagateError() throws InterruptedException {
        RuntimeException err = new RuntimeException("boom");
        Flow.Publisher<LlmEvent> pub = LlmFlowAdapter.adapt(Flux.error(err));
        AtomicReference<Throwable> recv = new AtomicReference();
        CountDownLatch latch = new CountDownLatch(1);

        pub.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(LlmEvent e) { fail("不应收到事件"); }
            @Override public void onError(Throwable t) { recv.set(t); latch.countDown(); }
            @Override public void onComplete() { fail("不应完成"); }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertSame(err, recv.get());
    }

    // ========== Mock 辅助 ==========

    private ChatResponse mockChatResponseWithContent(String content) {
        AssistantMessage msg = new AssistantMessage(content == null ? "" : content);
        return new ChatResponse(List.of(new Generation(msg)));
    }

    private ChatResponse mockChatResponseWithToolCall(ToolCall toolCall) {
        AssistantMessage msg = AssistantMessage.builder()
                .content("").toolCalls(List.of(toolCall)).build();
        return new ChatResponse(List.of(new Generation(msg)));
    }

    private ChatResponse mockChatResponseWithFinishReason(String finishReason) {
        ChatGenerationMetadata md = new ChatGenerationMetadata() {
            @Override public String getFinishReason() { return finishReason; }
            @Override public java.util.Set<String> getContentFilters() { return java.util.Set.of(); }
            @Override public <T> T get(String k) { return null; }
            @Override public boolean containsKey(String k) { return false; }
            @Override public <T> T getOrDefault(String k, T d) { return d; }
            @Override public java.util.Set<java.util.Map.Entry<String, Object>> entrySet() { return java.util.Set.of(); }
            @Override public java.util.Set<String> keySet() { return java.util.Set.of(); }
            @Override public boolean isEmpty() { return true; }
        };
        return new ChatResponse(List.of(new Generation(new AssistantMessage(""), md)));
    }
}