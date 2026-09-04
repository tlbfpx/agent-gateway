package com.company.agentgateway.application.orchestration;

import com.company.agentgateway.domain.session.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 保留最近 N 条历史（默认策略）。简单、可预测、无额外 LLM 开销。
 *
 * <p>N 可配置（gateway.llm.history.max-messages，默认 40 ≈ 20 轮对话）。
 * 工具循环中的本轮消息（roundContext）总是保留。
 */
public class LastNHistoryPolicy implements HistoryPolicy {

    private final int maxMessages;

    public LastNHistoryPolicy(int maxMessages) {
        if (maxMessages < 2) {
            throw new IllegalArgumentException("maxMessages >= 2");
        }
        this.maxMessages = maxMessages;
    }

    @Override
    public List<Message> assemble(List<Message> fullHistory, List<Message> roundContext) {
        List<Message> all = new ArrayList<>(fullHistory.size() + roundContext.size());
        all.addAll(fullHistory);
        all.addAll(roundContext);
        if (all.size() <= maxMessages) {
            return List.copyOf(all);
        }
        return List.copyOf(all.subList(all.size() - maxMessages, all.size()));
    }

    @Override
    public String name() {
        return "last-" + maxMessages;
    }
}
