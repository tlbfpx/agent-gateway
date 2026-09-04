package com.company.agentgateway.application.orchestration;

import com.company.agentgateway.domain.session.Message;

import java.util.List;

/**
 * 会话历史组装策略（可插拔，spec §5.3 上下文窗口）。
 *
 * <p>编排器在每次调用 LLM 前经本策略把「会话全量历史 + 本轮新增消息」组装为
 * 注入 LLM 的上下文。策略决定裁剪/压缩/保留方式——可替换：
 * <ul>
 *   <li>{@link LastNHistoryPolicy}：保留最近 N 条（默认，简单可预测）</li>
 *   <li>TokenBudgetHistoryPolicy：按 token 预算裁剪（二期，接真实 tokenizer）</li>
 *   <li>SummarizingHistoryPolicy：早期轮次滚动摘要（二期）</li>
 * </ul>
 *
 * <p>实现必须无状态（编排器并发调用）。
 */
public interface HistoryPolicy {

    /**
     * 组装注入 LLM 的历史。
     *
     * @param fullHistory 会话全量历史（持久化的所有消息，按时间序）
     * @param roundContext 本轮工具循环中新增的消息（tool_call/tool_result 等）
     * @return 裁剪后的上下文（不含本轮用户 prompt——由 LlmSession 单独追加）
     */
    List<Message> assemble(List<Message> fullHistory, List<Message> roundContext);

    /** 策略名（诊断用）。 */
    String name();
}
