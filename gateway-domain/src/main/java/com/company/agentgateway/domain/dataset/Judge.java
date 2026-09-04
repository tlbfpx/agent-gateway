package com.company.agentgateway.domain.dataset;

import java.util.List;

/**
 * LLM-as-judge 评测端口（spec 2026-09-02 §llm-as-judge §3）。
 *
 * <p>对一个 (input, expected, actual) 三元组,由 LLM 判定是否通过。
 * 实现：
 * <ul>
 *   <li>P0：{@code StubJudge} —— 基于关键词/长度的启发式</li>
 *   <li>P1：{@code LlmJudge} —— 接 ChatOrchestrator 调真实 LLM</li>
 * </ul>
 */
public interface Judge {

    /**
     * 评测单条 case;返回 true/false + 解释。
     *
     * @param input       测试输入
     * @param expected    期望输出
     * @param actual      模型实际输出
     * @param rubric      评分提示(可选,如 "判定是否语义等价")
     * @return verdict + 解释
     */
    Verdict judge(String input, String expected, String actual, String rubric);

    /** 批量评测(可用于 report) */
    default List<Verdict> judgeBatch(List<Trio> trios, String rubric) {
        return trios.stream().map(t -> judge(t.input(), t.expected(), t.actual(), rubric)).toList();
    }

    /** 评测三元组 (input/expected/actual) */
    record Trio(String input, String expected, String actual) {}

    /** 评测判定结果 */
    record Verdict(boolean pass, String reason, double confidence) {
        public static Verdict pass(String reason) {
            return new Verdict(true, reason, 1.0);
        }
        public static Verdict fail(String reason) {
            return new Verdict(false, reason, 1.0);
        }
    }
}