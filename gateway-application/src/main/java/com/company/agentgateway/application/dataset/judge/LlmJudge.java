package com.company.agentgateway.application.dataset.judge;

import com.company.agentgateway.domain.dataset.Judge;
import com.company.agentgateway.domain.dataset.JudgeLlmPort;
import com.company.agentgateway.infra.persistence.judge.StubJudge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-as-judge 实现（spec 2026-09-02 §llm-judge §5 R17 #1）。
 *
 * <p>三层 fallback:
 * <ol>
 *   <li>{@link JudgeLlmPort} 存在(真实 LLM 接入)→ 调 LLM 解析 PASS/FAIL</li>
 *   <li>无 JudgeLlmPort 但有 StubJudge → 启发式</li>
 *   <li>都没有 → fail-closed(显式 fail + 原因)</li>
 * </ol>
 */
public class LlmJudge implements Judge {

    private static final Logger log = LoggerFactory.getLogger(LlmJudge.class);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(\\w+)\\s*\\}\\}");
    private static final Pattern PASS_FAIL = Pattern.compile(
            "\\b(PASS|FAIL)\\b", Pattern.CASE_INSENSITIVE);

    private final String systemPrompt;
    private final Judge delegate;
    private final JudgeLlmPort llmPort;

    public LlmJudge() {
        this(DEFAULT_SYSTEM_PROMPT, null, null);
    }

    /** P0 构造:无 LLM,纯 StubJudge */
    public LlmJudge(String systemPrompt, Judge delegate) {
        this(systemPrompt, delegate, null);
    }

    /** R17 #1 构造:可注入 JudgeLlmPort */
    public LlmJudge(String systemPrompt, Judge delegate, JudgeLlmPort llmPort) {
        this.systemPrompt = systemPrompt;
        this.delegate = delegate;
        this.llmPort = llmPort;
    }

    public static final String DEFAULT_SYSTEM_PROMPT =
            "你是评分员。判定 expected 与 actual 语义是否一致。\n" +
            "输入: {{input}}\n期望: {{expected}}\n实际: {{actual}}\n" +
            "请仅输出 PASS 或 FAIL(不解释)。";

    @Override
    public Verdict judge(String input, String expected, String actual, String rubric) {
        String userPrompt = render(systemPrompt, input, expected, actual);

        // 优先用真实 LLM
        if (llmPort != null && llmPort.isAvailable()) {
            try {
                String response = llmPort.complete(
                        "你是评分员,只输出 PASS 或 FAIL,不带任何解释。",
                        userPrompt, "gpt-4o-mini", 0.0);
                log.debug("llm.judge.response length={}", response.length());
                return parseVerdict(response, rubric);
            } catch (Exception ex) {
                log.warn("llm.judge.error, fallback to stub: {}", ex.getMessage());
            }
        }

        // fallback 到 StubJudge
        if (delegate != null) {
            Verdict v = delegate.judge(input, expected, actual, rubric);
            return new Verdict(v.pass(),
                    "[" + (rubric == null ? "" : rubric) + "] " + v.reason(),
                    v.confidence() * 0.8);  // 启发式降权
        }
        // fail-closed
        return Verdict.fail("no LLM port and no delegate judge configured");
    }

    static String render(String template, String input, String expected, String actual) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = switch (key) {
                case "input" -> input == null ? "" : input;
                case "expected" -> expected == null ? "" : expected;
                case "actual" -> actual == null ? "" : actual;
                default -> "";
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static Verdict parseVerdict(String response, String rubric) {
        if (response == null || response.isBlank()) {
            return Verdict.fail("empty LLM response");
        }
        Matcher m = PASS_FAIL.matcher(response.trim());
        if (!m.find()) {
            return Verdict.fail("LLM response has no PASS/FAIL: " + abbreviate(response));
        }
        boolean pass = m.group(1).equalsIgnoreCase("PASS");
        double confidence = 0.95;
        return new Verdict(pass,
                "[" + (rubric == null ? "" : rubric) + "] LLM-judge=" + m.group(1).toUpperCase(),
                confidence);
    }

    private static String abbreviate(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}