package com.company.agentgateway.infra.persistence.judge;

import com.company.agentgateway.domain.dataset.Judge;
import com.company.agentgateway.domain.dataset.Judge.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StubJudgeTest {

    private Judge judge;

    @BeforeEach
    void setUp() {
        judge = new StubJudge();
    }

    @Test
    void exactMatch_caseInsensitive_passes() {
        Verdict v = judge.judge("input", "Hello World", "hello world", null);
        assertTrue(v.pass());
    }

    @Test
    void actualContainsExpected_passes() {
        Verdict v = judge.judge("input", "Paris", "The capital of France is Paris.", null);
        assertTrue(v.pass());
    }

    @Test
    void tokenOverlap_aboveThreshold_passes() {
        Verdict v = judge.judge("input",
                "the capital of france is paris",
                "paris is the capital city of france", null);
        assertTrue(v.pass());
    }

    @Test
    void openQuestion_emptyExpected_passes() {
        Verdict v = judge.judge("Hi", "", "Hello, how can I help?", null);
        assertTrue(v.pass());
    }

    @Test
    void polarityConflict_expectedYesActualNo_fails() {
        Verdict v = judge.judge("Is 2+2=4?", "Yes correct", "No incorrect", null);
        assertFalse(v.pass());
    }

    @Test
    void polarityConflict_expectedNoActualYes_fails() {
        Verdict v = judge.judge("Is sky green?", "No incorrect", "Yes correct", null);
        assertFalse(v.pass());
    }

    @Test
    void noMatch_fails() {
        Verdict v = judge.judge("What is 2+2?", "4", "Elephants are gray.", null);
        assertFalse(v.pass());
    }

    @Test
    void batch_returnsSameAsIndividual() {
        Verdict v1 = judge.judge("i1", "Paris", "The capital is Paris.", null);
        List<Judge.Trio> trios = List.of(
                new Judge.Trio("i1", "Paris", "The capital is Paris."),
                new Judge.Trio("i2", "4", "Elephants."));
        var batch = judge.judgeBatch(trios, null);
        assertEquals(2, batch.size());
        assertTrue(batch.get(0).pass());
        assertFalse(batch.get(1).pass());
    }
}