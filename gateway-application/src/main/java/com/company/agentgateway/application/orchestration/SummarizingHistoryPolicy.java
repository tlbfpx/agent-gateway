package com.company.agentgateway.application.orchestration;

import com.company.agentgateway.domain.session.AssistantMessage;
import com.company.agentgateway.domain.session.Message;
import com.company.agentgateway.domain.session.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 滚动摘要历史策略（spec §5.3 二期）：
 * 早期轮次压缩为一条摘要消息，近 keepRecent 条保留原文。
 *
 * <p>摘要由外部 summarizer 函数生成（编排器注入——可用小模型或规则实现，
 * 保持本策略纯逻辑可测）。摘要结果可缓存（key=摘要范围）避免每轮重算——
 * 本类内部按「已摘要条数」增量摘要：只摘要新增部分并与旧摘要拼接。
 *
 * <p>可插拔：{@code gateway.llm.history.policy=summarizing} 切换。
 */
public class SummarizingHistoryPolicy implements HistoryPolicy {

    private final int keepRecent;
    private final int summarizeTrigger;   // 超过此条数才触发摘要
    private final Function<List<Message>, String> summarizer;

    // 增量状态（单会话内复用同一 policy 实例时生效；跨会话由编排器按会话持有）
    private int summarizedCount = 0;
    private String summarySoFar = "";

    public SummarizingHistoryPolicy(int keepRecent, int summarizeTrigger,
                                    Function<List<Message>, String> summarizer) {
        if (keepRecent < 2) throw new IllegalArgumentException("keepRecent >= 2");
        this.keepRecent = keepRecent;
        this.summarizeTrigger = Math.max(summarizeTrigger, keepRecent + 1);
        this.summarizer = summarizer;
    }

    @Override
    public List<Message> assemble(List<Message> fullHistory, List<Message> roundContext) {
        List<Message> all = new ArrayList<>(fullHistory.size() + roundContext.size());
        all.addAll(fullHistory);
        all.addAll(roundContext);

        // 未超触发线：全量保留（等价 LastN(∞)，交给模型上下文上限兜底）
        if (all.size() <= summarizeTrigger) {
            return List.copyOf(all);
        }

        // 超线：旧摘要 + 增量摘要新增部分 + 最近 keepRecent 原文
        List<Message> toSummarize = all.subList(0, all.size() - keepRecent);
        if (toSummarize.size() > summarizedCount) {
            String newSummary = summarizer.apply(
                    toSummarize.subList(summarizedCount, toSummarize.size()));
            summarySoFar = summarySoFar.isEmpty() ? newSummary : summarySoFar + "\n" + newSummary;
            summarizedCount = toSummarize.size();
        }

        List<Message> out = new ArrayList<>(2 + keepRecent);
        out.add(new UserMessage("[此前对话摘要]"));
        out.add(new AssistantMessage(summarySoFar));
        out.addAll(all.subList(all.size() - keepRecent, all.size()));
        return out;
    }

    @Override
    public String name() {
        return "summarizing(keep=" + keepRecent + ",trigger=" + summarizeTrigger + ")";
    }

    /** 当前累计摘要（诊断/测试用）。 */
    public String currentSummary() { return summarySoFar; }
}
