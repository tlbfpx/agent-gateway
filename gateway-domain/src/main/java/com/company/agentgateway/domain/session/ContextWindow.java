package com.company.agentgateway.domain.session;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * spec §5.3：注入 LLM 前的历史裁剪。
 * 一期算法：Token 预算截断（从最早向前丢弃，但至少保留最近 minKeep 条）。
 */
public final class ContextWindow {
    private final int minKeep;
    private final ToIntFunction<Message> tokenEstimator;

    public ContextWindow(int minKeep, ToIntFunction<Message> tokenEstimator) {
        if (minKeep < 1) throw new IllegalArgumentException("minKeep >= 1");
        this.minKeep = minKeep;
        this.tokenEstimator = tokenEstimator;
    }

    public List<Message> fit(List<Message> history, int tokenBudget) {
        if (history.size() <= minKeep) return List.copyOf(history);
        int total = history.stream().mapToInt(tokenEstimator).sum();
        if (total <= tokenBudget) return List.copyOf(history);
        // 从最早丢弃直到满足预算或只剩 minKeep
        List<Message> work = new ArrayList<>(history);
        int total2 = total;
        while (total2 > tokenBudget && work.size() > minKeep) {
            total2 -= tokenEstimator.applyAsInt(work.get(0));
            work.remove(0); // 总是从头部移除
        }
        return List.copyOf(work);
    }
}
