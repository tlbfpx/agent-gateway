package com.company.agentgateway.infra.persistence.judge;

import com.company.agentgateway.domain.dataset.Judge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 启发式 Judge 实现（spec 2026-09-02 §llm-as-judge §4 P0）。
 *
 * <p>不接真实 LLM;用 5 条启发式模拟 LLM 评判:
 * <ol>
 *   <li>完全相等(忽略大小写/空格)→ PASS</li>
 *   <li>expected 为空 + actual 非空 → PASS(开放题)</li>
 *   <li>expected 完全包含在 actual 中(忽略大小写)→ PASS</li>
 *   <li>关键 token 集合(expected 拆词,≥80% 在 actual 中)→ PASS</li>
 *   <li>否定词检测:expected 说\"是/对\",actual 说\"否/错/不\" → FAIL(反之亦然)</li>
 * </ol>
 *
 * <p>R14 替换为 {@code LlmJudge}(接 ChatOrchestrator);只需替换 bean。
 */
public class StubJudge implements Judge {

    private static final Logger log = LoggerFactory.getLogger(StubJudge.class);

    private static final Set<String> NEGATIVE_WORDS = Set.of(
            "no", "not", "否", "错", "不对", "false", "incorrect", "wrong");
    private static final Set<String> POSITIVE_WORDS = Set.of(
            "yes", "对", "是", "正确", "correct", "right", "true");

    @Override
    public Verdict judge(String input, String expected, String actual, String rubric) {
        String e = expected == null ? "" : expected.trim();
        String a = actual == null ? "" : actual.trim();
        if (e.isEmpty() && !a.isEmpty()) {
            return Verdict.pass("open question (no expected)");
        }
        if (e.equalsIgnoreCase(a)) {
            return Verdict.pass("exact match (case-insensitive)");
        }
        String eLower = e.toLowerCase(Locale.ROOT);
        String aLower = a.toLowerCase(Locale.ROOT);
        if (aLower.contains(eLower)) {
            return Verdict.pass("actual contains expected");
        }
        // token overlap ≥80%
        Set<String> expectedTokens = tokenize(e);
        Set<String> actualTokens = tokenize(a);
        if (!expectedTokens.isEmpty()) {
            long hits = expectedTokens.stream().filter(actualTokens::contains).count();
            double overlap = (double) hits / expectedTokens.size();
            if (overlap >= 0.8) {
                return Verdict.pass("token overlap " + String.format("%.0f%%", overlap * 100));
            }
        }
        // 否定词冲突
        boolean expectedNegative = containsAny(eLower, NEGATIVE_WORDS);
        boolean expectedPositive = containsAny(eLower, POSITIVE_WORDS);
        boolean actualNegative = containsAny(aLower, NEGATIVE_WORDS);
        boolean actualPositive = containsAny(aLower, POSITIVE_WORDS);
        if (expectedNegative && actualPositive) {
            return Verdict.fail("polarity conflict: expected negative, actual positive");
        }
        if (expectedPositive && actualNegative) {
            return Verdict.fail("polarity conflict: expected positive, actual negative");
        }
        log.debug("judge.stub.fail expected=\"{}\" actual=\"{}\"", abbreviate(e), abbreviate(a));
        return Verdict.fail("no rule matched");
    }

    private static Set<String> tokenize(String s) {
        Set<String> tokens = new HashSet<>();
        for (String t : s.split("[\\s,;.!?，。；！？]+")) {
            String lower = t.toLowerCase(Locale.ROOT);
            if (!lower.isBlank() && lower.length() >= 2) tokens.add(lower);
        }
        return tokens;
    }

    private static boolean containsAny(String haystack, Set<String> needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }
}