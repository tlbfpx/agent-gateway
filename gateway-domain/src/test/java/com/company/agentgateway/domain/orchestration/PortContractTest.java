package com.company.agentgateway.domain.orchestration;
import com.company.agentgateway.domain.iam.*;
import com.company.agentgateway.domain.shared.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import static org.assertj.core.api.Assertions.assertThat;

class PortContractTest {
    private InvocationCtx ctx() {
        var p = new AuthPrincipal(new UserId("u"), new TenantId("t"), java.util.Set.of(),
            java.util.Set.of(), AuthChannel.API_KEY);
        return new InvocationCtx(new SessionId("s"), p, "trace-1");
    }

    @Test
    void toolPortRealSubscriptionDeliversDeltaThenComplete() throws Exception {
        ToolPort port = (agent, args, c) -> subscriber -> {
            subscriber.onNext(new ToolEvent.Delta("hello"));
            subscriber.onNext(new ToolEvent.Complete("hello full"));
            subscriber.onComplete();
        };
        var card = new com.company.agentgateway.domain.registry.AgentCard("a", "d", List.of(), "{}", "{}", "1", true, "https://agent.example/a2a/invoke");
        var received = new ConcurrentLinkedQueue<ToolEvent>();
        var done = new CountDownLatch(1);
        port.invoke(card, "{}", ctx()).subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(ToolEvent e) { received.add(e); }
            public void onError(Throwable t) {}
            public void onComplete() { done.countDown(); }
        });
        assertThat(done.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(received).extracting(Object::getClass)
            .contains(ToolEvent.Delta.class, ToolEvent.Complete.class);
    }

    @Test
    void agentCardPortSnapshotReturnsList() {
        AgentCardPort port = new AgentCardPort() {
            public List<com.company.agentgateway.domain.registry.AgentCard> snapshot() { return List.of(); }
            public Flow.Publisher<List<com.company.agentgateway.domain.registry.AgentCard>> watch() { return s -> s.onComplete(); }
        };
        assertThat(port.snapshot()).isEmpty();
    }

    @Test
    void chatClientPortReturnsLlmSession() {
        ChatClientPort port = (model, tools) -> (prompt, history, c) -> s -> s.onComplete();
        var session = port.sessionFor(new ModelId("qwen"), List.of());
        assertThat(session).isNotNull();
    }

    @Test
    void toolEventDeltaPreservesContent() {
        var event = new ToolEvent.Delta("partial result");
        assertThat(event.content()).isEqualTo("partial result");
    }

    @Test
    void toolEventCompletePreservesResult() {
        var event = new ToolEvent.Complete("final result");
        assertThat(event.fullResult()).isEqualTo("final result");
    }

    @Test
    void toolEventErrorPreservesFields() {
        var event = new ToolEvent.Error("TIMEOUT", "Operation timed out");
        assertThat(event.code()).isEqualTo("TIMEOUT");
        assertThat(event.message()).isEqualTo("Operation timed out");
    }

    @Test
    void llmEventDeltaPreservesContent() {
        var event = new LlmEvent.Delta("text");
        assertThat(event.content()).isEqualTo("text");
    }

    @Test
    void llmEventToolCallPreservesFields() {
        var event = new LlmEvent.ToolCall("calculator", "{\"x\":1}");
        assertThat(event.toolName()).isEqualTo("calculator");
        assertThat(event.argsJson()).isEqualTo("{\"x\":1}");
    }

    @Test
    void llmEventCompleteIsSingleton() {
        var event = new LlmEvent.Complete();
        assertThat(event).isNotNull();
    }

    @Test
    void invocationCtxPreservesFields() {
        var p = new AuthPrincipal(new UserId("u"), new TenantId("t"), java.util.Set.of(),
            java.util.Set.of(), AuthChannel.API_KEY);
        var ctx = new InvocationCtx(new SessionId("s"), p, "trace-123");
        assertThat(ctx.session().value()).isEqualTo("s");
        assertThat(ctx.principal()).isEqualTo(p);
        assertThat(ctx.traceId()).isEqualTo("trace-123");
    }

    @Test
    void toolDescriptorPreservesFields() {
        var desc = new ToolDescriptor("myTool", "A test tool", "{\"type\":\"object\"}");
        assertThat(desc.name()).isEqualTo("myTool");
        assertThat(desc.description()).isEqualTo("A test tool");
        assertThat(desc.inputSchemaJson()).isEqualTo("{\"type\":\"object\"}");
    }

    @Test
    void promptCachePortGetPutContract() {
        java.time.Instant now = java.time.Instant.now();
        var entry = new PromptCachePort.CacheEntry("answer", now, "qwen");
        java.util.Map<String, PromptCachePort.CacheEntry> store = new java.util.concurrent.ConcurrentHashMap<>();
        PromptCachePort port = new PromptCachePort() {
            public java.util.Optional<PromptCachePort.CacheEntry> get(String key) { return java.util.Optional.ofNullable(store.get(key)); }
            public void put(String key, PromptCachePort.CacheEntry e) { store.put(key, e); }
        };
        assertThat(port.get("k")).isEmpty();
        port.put("k", entry);
        assertThat(port.get("k")).contains(entry);
        assertThat(entry.answer()).isEqualTo("answer");
        assertThat(entry.createdAt()).isEqualTo(now);
        assertThat(entry.model()).isEqualTo("qwen");
    }
}
