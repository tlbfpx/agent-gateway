package com.company.agentgateway.application.orchestration;

import com.company.agentgateway.domain.session.Message;
import com.company.agentgateway.domain.session.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LastNHistoryPolicyTest {

    @Test
    void 少于N条全保留() {
        var policy = new LastNHistoryPolicy(10);
        var result = policy.assemble(List.of(new UserMessage("a"), new UserMessage("b")), List.of());
        assertThat(result).hasSize(2);
    }

    @Test
    void 超过N条只留最近N() {
        var policy = new LastNHistoryPolicy(3);
        var history = List.<Message>of(
                new UserMessage("1"), new UserMessage("2"), new UserMessage("3"),
                new UserMessage("4"), new UserMessage("5"));
        var result = policy.assemble(history, List.of());
        assertThat(result).hasSize(3);
        assertThat(((UserMessage) result.get(2)).content()).isEqualTo("5");
    }

    @Test
    void 本轮工具上下文计入并保留() {
        var policy = new LastNHistoryPolicy(2);
        var history = List.<Message>of(new UserMessage("old1"), new UserMessage("old2"));
        var round = List.<Message>of(new UserMessage("tool-result"));
        var result = policy.assemble(history, round);
        assertThat(result).hasSize(2);
        // 最近 2 条 = old2 + tool-result
        assertThat(((UserMessage) result.get(1)).content()).isEqualTo("tool-result");
    }
}
