package com.company.agentgateway.domain.dataset;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalDatasetTest {

    @Test
    void create_setsDefaults() {
        EvalDataset d = EvalDataset.create("regression-suite", "smoke test", "au", 1L, null);
        assertEquals(0L, d.id());
        assertEquals("regression-suite", d.name());
        assertNotNull(d.createdAt());
    }

    @Test
    void rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> EvalDataset.create("", "x", "au", 1L, null));
        assertThrows(IllegalArgumentException.class, () -> EvalDataset.create("n", "x", "", 1L, null));
        assertThrows(IllegalArgumentException.class, () -> EvalDataset.create("n", "x", "au", 0L, null));
    }
}

class EvalCaseTest {
    @Test
    void create_defaultsWeight() {
        EvalCase c = EvalCase.create(1L, "hi", "hello", null, 0);
        assertEquals(1, c.weight());
        assertEquals("hi", c.input());
    }

    @Test
    void rejectsZeroDatasetId() {
        assertThrows(IllegalArgumentException.class, () -> EvalCase.create(0L, "x", "y", null, 1));
    }
}

class EvalStrategyTest {
    @Test
    void exact_caseInsensitive() {
        assertTrue(EvalStrategy.EXACT.pass("Hello", "hello"));
        assertTrue(EvalStrategy.EXACT.pass("hello", "HELLO"));
        assertFalse(EvalStrategy.EXACT.pass("hello!", "hello"));
    }

    @Test
    void exact_trimsWhitespace() {
        assertTrue(EvalStrategy.EXACT.pass("  hello  ", "hello"));
    }

    @Test
    void contains_substring() {
        assertTrue(EvalStrategy.CONTAINS.pass("the quick brown fox", "brown"));
        assertFalse(EvalStrategy.CONTAINS.pass("the quick brown fox", "lazy"));
    }

    @Test
    void regex_match() {
        assertTrue(EvalStrategy.REGEX.pass("abc 123", "\\d+"));
        assertFalse(EvalStrategy.REGEX.pass("abc xyz", "\\d+"));
    }

    @Test
    void regex_invalid_returnsFalse() {
        assertFalse(EvalStrategy.REGEX.pass("x", "[unclosed"));
    }

    @Test
    void parse_knownNames() {
        assertEquals(EvalStrategy.EXACT, EvalStrategy.parse("exact"));
        assertEquals(EvalStrategy.CONTAINS, EvalStrategy.parse("CONTAINS"));
        assertEquals(EvalStrategy.REGEX, EvalStrategy.parse("regex"));
    }

    @Test
    void parse_rejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> EvalStrategy.parse("fuzzy"));
        assertThrows(IllegalArgumentException.class, () -> EvalStrategy.parse(""));
        assertThrows(IllegalArgumentException.class, () -> EvalStrategy.parse(null));
    }
}

class EvalRunTest {
    @Test
    void create_defaultsStatusPending() {
        EvalRun r = new EvalRun(0L, 1L, 1L, "gpt-4o", EvalStrategy.EXACT, null,
                EvalRun.RunMetrics.empty(), null, "au", 7L, null, null);
        assertEquals(EvalRun.Status.PENDING, r.status());
    }

    @Test
    void rejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> new EvalRun(
                0L, 0L, 1L, "gpt-4o", null, null, null, null, "au", 7L, null, null));
        assertThrows(IllegalArgumentException.class, () -> new EvalRun(
                0L, 1L, 0L, "gpt-4o", null, null, null, null, "au", 7L, null, null));
        assertThrows(IllegalArgumentException.class, () -> new EvalRun(
                0L, 1L, 1L, "", null, null, null, null, "au", 7L, null, null));
    }
}

class EvalCaseResultTest {
    @Test
    void create_basic() {
        EvalCaseResult r = new EvalCaseResult(1L, "out", true, 1.0, 100);
        assertTrue(r.passed());
        assertEquals(100, r.latencyMs());
    }

    @Test
    void rejectsScoreOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> new EvalCaseResult(1L, "x", true, 1.5, 0));
        assertThrows(IllegalArgumentException.class, () -> new EvalCaseResult(1L, "x", true, -0.1, 0));
    }
}
