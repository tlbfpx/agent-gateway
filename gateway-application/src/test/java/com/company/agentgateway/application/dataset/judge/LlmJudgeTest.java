package com.company.agentgateway.application.dataset.judge;

import com.company.agentgateway.domain.dataset.Judge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmJudgeTest {

    @Test
    void judge_delegatesToStub() {
        // R17 #1 改造:无 LLM 时走 StubJudge delegate
        LlmJudge judge = new LlmJudge(
                LlmJudge.DEFAULT_SYSTEM_PROMPT,
                new com.company.agentgateway.infra.persistence.judge.StubJudge(),
                null);
        Judge.Verdict v = judge.judge("input", "Paris", "The capital is Paris.", null);
        assertTrue(v.pass());
    }

    @Test
    void judge_failsOnMismatch() {
        LlmJudge judge = new LlmJudge();
        Judge.Verdict v = judge.judge("input", "4", "Elephants.", null);
        assertFalse(v.pass());
    }

    @Test
    void render_substitutesPlaceholders() {
        String rendered = LlmJudge.render(
                "in={{input}} exp={{expected}} out={{actual}}",
                "i", "e", "a");
        assertEquals("in=i exp=e out=a", rendered);
    }

    @Test
    void render_handlesUnknownPlaceholder() {
        String rendered = LlmJudge.render("hi {{unknown}} {{input}}", "X", null, null);
        assertEquals("hi  X", rendered);
    }

    @Test
    void verdictReason_includesRubric() {
        LlmJudge judge = new LlmJudge(
                LlmJudge.DEFAULT_SYSTEM_PROMPT,
                new com.company.agentgateway.infra.persistence.judge.StubJudge(),
                null);
        Judge.Verdict v = judge.judge("i", "Paris", "The capital is Paris.", "semantic");
        assertNotNull(v.reason());
        assertTrue(v.reason().contains("semantic"));
    }
}