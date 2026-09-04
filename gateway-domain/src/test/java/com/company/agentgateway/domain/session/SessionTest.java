package com.company.agentgateway.domain.session;

import com.company.agentgateway.domain.shared.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class SessionTest {
    private Session newSession() {
        return new Session(new SessionId("s1"), new TenantId("t1"), new UserId("u1"),
            new ModelId("qwen"), Instant.parse("2026-08-12T00:00:00Z"),
            Instant.parse("2026-08-12T00:00:00Z"), java.util.List.of());
    }

    @Test
    void appendAddsMessageAndKeepsHistory() {
        var s2 = newSession().append(new UserMessage("hi"));
        assertThat(s2.history()).hasSize(1);
    }

    @Test
    void appendSlimmsOversizedToolResult() {
        // spec §5.3 一期：超大 ToolResult 替换为占位摘要，完整结果由 infra 持久化
        String big = "x".repeat(2000);  // 超过阈值 1000
        var s2 = newSession().append(new ToolResultMessage("agent-x", big, false));
        var tr = (ToolResultMessage) s2.history().get(0);
        assertThat(tr.slimmed()).isTrue();
        assertThat(tr.content()).hasSizeLessThan(big.length());
    }

    @Test
    void appendKeepsSmallToolResultAsIs() {
        var s2 = newSession().append(new ToolResultMessage("agent-x", "small", false));
        var tr = (ToolResultMessage) s2.history().get(0);
        assertThat(tr.slimmed()).isFalse();
    }

    @Test
    void assistantMessagePreservesContent() {
        var msg = new AssistantMessage("Hello from assistant");
        assertThat(msg.content()).isEqualTo("Hello from assistant");
    }

    @Test
    void toolCallMessagePreservesFields() {
        var msg = new ToolCallMessage("calculator", "{\"expression\":\"1+1\"}");
        assertThat(msg.agentName()).isEqualTo("calculator");
        assertThat(msg.argsJson()).isEqualTo("{\"expression\":\"1+1\"}");
    }

    @Test
    void toolResultMessagePreservesFields() {
        var msg = new ToolResultMessage("calculator", "2", false);
        assertThat(msg.agentName()).isEqualTo("calculator");
        assertThat(msg.content()).isEqualTo("2");
        assertThat(msg.slimmed()).isFalse();
    }
}
