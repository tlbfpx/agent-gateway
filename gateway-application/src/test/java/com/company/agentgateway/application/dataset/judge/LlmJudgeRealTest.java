package com.company.agentgateway.application.dataset.judge;

import com.company.agentgateway.domain.dataset.Judge;
import com.company.agentgateway.domain.dataset.JudgeLlmPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R17 #1 LlmJudge 真实 LLM 路径测试(mock JudgeLlmPort)。
 */
class LlmJudgeRealTest {

    @Test
    void realLlm_passPath() {
        JudgeLlmPort mockLlm = new MockJudgeLlmPort("PASS");
        LlmJudge judge = new LlmJudge(LlmJudge.DEFAULT_SYSTEM_PROMPT, null, mockLlm);
        Judge.Verdict v = judge.judge("input", "Paris", "The capital is Paris.", "semantic");
        assertTrue(v.pass());
        assertTrue(v.reason().contains("PASS"));
        assertTrue(v.reason().contains("semantic"));
    }

    @Test
    void realLlm_failPath() {
        JudgeLlmPort mockLlm = new MockJudgeLlmPort("FAIL");
        LlmJudge judge = new LlmJudge(LlmJudge.DEFAULT_SYSTEM_PROMPT, null, mockLlm);
        Judge.Verdict v = judge.judge("input", "4", "Elephants.", null);
        assertFalse(v.pass());
        assertTrue(v.reason().contains("FAIL"));
    }

    @Test
    void realLlm_responseInSentence_extractsPass() {
        JudgeLlmPort mockLlm = new MockJudgeLlmPort("Looking at this, my verdict is PASS.");
        LlmJudge judge = new LlmJudge(LlmJudge.DEFAULT_SYSTEM_PROMPT, null, mockLlm);
        Judge.Verdict v = judge.judge("i", "4", "Four.", null);
        assertTrue(v.pass());
    }

    @Test
    void realLlm_caseInsensitive() {
        JudgeLlmPort mockLlm = new MockJudgeLlmPort("pass");
        LlmJudge judge = new LlmJudge(LlmJudge.DEFAULT_SYSTEM_PROMPT, null, mockLlm);
        assertTrue(judge.judge("i", "x", "x", null).pass());
    }

    @Test
    void realLlm_noPassOrFail_returnsFail() {
        JudgeLlmPort mockLlm = new MockJudgeLlmPort("I'm not sure what to say");
        LlmJudge judge = new LlmJudge(LlmJudge.DEFAULT_SYSTEM_PROMPT, null, mockLlm);
        Judge.Verdict v = judge.judge("i", "x", "y", null);
        assertFalse(v.pass());
        assertTrue(v.reason().contains("no PASS/FAIL"));
    }

    @Test
    void realLlm_emptyResponse_returnsFail() {
        JudgeLlmPort mockLlm = new MockJudgeLlmPort("");
        LlmJudge judge = new LlmJudge(LlmJudge.DEFAULT_SYSTEM_PROMPT, null, mockLlm);
        assertFalse(judge.judge("i", "x", "y", null).pass());
    }

    @Test
    void realLlm_throws_fallbackToStub() {
        JudgeLlmPort throwingLlm = new ThrowingJudgeLlmPort();
        LlmJudge judge = new LlmJudge(
                LlmJudge.DEFAULT_SYSTEM_PROMPT, new com.company.agentgateway.infra.persistence.judge.StubJudge(), throwingLlm);
        // StubJudge 把 expected 4 vs actual "Four" 判 fail(无 token overlap)
        Judge.Verdict v = judge.judge("math", "4", "Four", null);
        // 不抛异常,fallback 成功
        assertNotNull(v);
    }

    @Test
    void noLlm_noDelegate_failsClosed() {
        LlmJudge judge = new LlmJudge(LlmJudge.DEFAULT_SYSTEM_PROMPT, null, null);
        Judge.Verdict v = judge.judge("i", "x", "y", null);
        assertFalse(v.pass());
        assertTrue(v.reason().contains("no LLM"));
    }

    @Test
    void parseVerdict_direct() {
        assertTrue(LlmJudge.parseVerdict("PASS", null).pass());
        assertTrue(LlmJudge.parseVerdict("pass", null).pass());
        assertTrue(LlmJudge.parseVerdict("verdict: PASS", null).pass());
        assertFalse(LlmJudge.parseVerdict("FAIL", null).pass());
        assertFalse(LlmJudge.parseVerdict("I think yes", null).pass());
    }

    // 静态方法断言
    private static void assertNotNull(Object o) { org.junit.jupiter.api.Assertions.assertNotNull(o); }

    static class MockJudgeLlmPort implements JudgeLlmPort {
        private final String response;
        MockJudgeLlmPort(String response) { this.response = response; }
        @Override public String complete(String sys, String user, String m, double t) { return response; }
        @Override public boolean isAvailable() { return true; }
    }

    static class ThrowingJudgeLlmPort implements JudgeLlmPort {
        @Override public String complete(String sys, String user, String m, double t) {
            throw new RuntimeException("LLM 503");
        }
        @Override public boolean isAvailable() { return true; }
    }
}