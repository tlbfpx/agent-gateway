package com.company.agentgateway.application.orchestration;

import com.company.agentgateway.domain.session.Message;
import com.company.agentgateway.domain.session.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SummarizingHistoryPolicyTest {

    private static Message u(String s) { return new UserMessage(s); }
    private static List<Message> msgs(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(i -> u("m" + i)).toList();
    }

    // 摘要器：把输入消息名拼接（可预测，便于断言）
    private static final Function<List<Message>, String> JOIN =
            list -> "SUM[" + list.stream().map(m -> ((UserMessage) m).content()).reduce((a, b) -> a + "," + b).orElse("") + "]";

    @Test
    void 未超触发线全量保留() {
        var p = new SummarizingHistoryPolicy(4, 8, JOIN);
        var out = p.assemble(msgs(8), List.of());
        assertThat(out).hasSize(8);
        assertThat(p.currentSummary()).isEmpty();
    }

    @Test
    void 超线后_摘要头部_最近N原文() {
        var p = new SummarizingHistoryPolicy(4, 8, JOIN);
        var out = p.assemble(msgs(12), List.of());
        // 结构：[摘要标记, 摘要, 最近4条]
        assertThat(out).hasSize(6);
        assertThat(((UserMessage) out.get(0)).content()).isEqualTo("[此前对话摘要]");
        assertThat(((com.company.agentgateway.domain.session.AssistantMessage) out.get(1)).content())
                .isEqualTo("SUM[m1,m2,m3,m4,m5,m6,m7,m8]");
        assertThat(((UserMessage) out.get(5)).content()).isEqualTo("m12");
    }

    @Test
    void 增量摘要_不重复计算() {
        var p = new SummarizingHistoryPolicy(4, 8, JOIN);
        p.assemble(msgs(12), List.of());            // 摘要 m1..m8
        p.assemble(msgs(14), List.of());            // 只摘要 m9..m10（12-keep 变化部分）
        assertThat(p.currentSummary()).contains("SUM[m1").contains("SUM[m9,m10]");
    }

    @Test
    void roundContext计入且在保留区() {
        var p = new SummarizingHistoryPolicy(4, 8, JOIN);
        var out = p.assemble(msgs(10), List.of(u("tool-r")));
        // 11 条 > 8 触发；最近4 = m8..m10 + tool-r
        assertThat(((UserMessage) out.get(out.size() - 1)).content()).isEqualTo("tool-r");
    }
}
