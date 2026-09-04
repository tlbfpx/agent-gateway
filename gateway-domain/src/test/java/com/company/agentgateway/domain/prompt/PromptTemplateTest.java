package com.company.agentgateway.domain.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptTemplateTest {

    @Test
    void create_setsDefaults() {
        PromptTemplate t = PromptTemplate.create("summarize", "summarize text", 1L, "au", List.of("nlp"));
        assertEquals(0L, t.id());
        assertEquals("summarize", t.name());
        assertEquals(List.of("nlp"), t.tags());
        assertNotNull(t.createdAt());
    }

    @Test
    void rejectsBlankNameAndTenant() {
        assertThrows(IllegalArgumentException.class, () -> PromptTemplate.create("", "x", 1L, "au", null));
        assertThrows(IllegalArgumentException.class, () -> PromptTemplate.create("n", "x", 1L, "", null));
    }

    @Test
    void rejectsZeroOwnerId() {
        assertThrows(IllegalArgumentException.class, () -> PromptTemplate.create("n", "x", 0L, "au", null));
    }
}

class PromptVersionTest {
    @Test
    void create_basic() {
        PromptVersion v = PromptVersion.create(
                1L, 1, "You are helpful.", "summarize {{text}}",
                "gpt-4o", Map.of("temperature", 0.3), 7L);
        assertEquals(0L, v.id());
        assertEquals(1, v.version());
        assertEquals("gpt-4o", v.model());
        assertEquals(Map.of("temperature", 0.3), v.params());
    }

    @Test
    void rejectsBlankPrompts() {
        assertThrows(IllegalArgumentException.class, () -> PromptVersion.create(
                1L, 1, null, null, "gpt-4o", null, 7L));
    }

    @Test
    void rejectsZeroVersion() {
        assertThrows(IllegalArgumentException.class, () -> PromptVersion.create(
                1L, 0, "sys", "user", "gpt-4o", null, 7L));
    }

    @Test
    void toMap_hasFlatShape() {
        PromptVersion v = PromptVersion.create(
                1L, 2, "sys", "user", "gpt-4o", Map.of("k", "v"), 7L);
        Map<String, Object> m = v.toMap();
        assertEquals("sys", m.get("systemPrompt"));
        assertEquals(2, m.get("version"));
    }
}

class PromptVariantTest {
    @Test
    void weightValid() {
        PromptVariant v = new PromptVariant(1L, 50, "control");
        assertEquals(50, v.weight());
        assertEquals("control", v.label());
    }

    @Test
    void rejectsInvalidWeight() {
        assertThrows(IllegalArgumentException.class, () -> new PromptVariant(1L, -1, "x"));
        assertThrows(IllegalArgumentException.class, () -> new PromptVariant(1L, 101, "x"));
    }

    @Test
    void rejectsZeroVersionId() {
        assertThrows(IllegalArgumentException.class, () -> new PromptVariant(0L, 50, "x"));
    }
}

class PromptExperimentTest {
    @Test
    void create_weightsMustSumTo100() {
        assertThrows(IllegalArgumentException.class, () -> new PromptExperiment(
                0L, 1L, "exp1", PromptExperiment.Status.DRAFT,
                List.of(new PromptVariant(1L, 30, "a"), new PromptVariant(2L, 30, "b")),
                "au", 7L, null));
    }

    @Test
    void create_succeedsAt100() {
        PromptExperiment e = new PromptExperiment(
                0L, 1L, "exp1", PromptExperiment.Status.DRAFT,
                List.of(new PromptVariant(1L, 50, "control"), new PromptVariant(2L, 50, "treatment")),
                "au", 7L, null);
        assertEquals(2, e.variants().size());
        assertEquals(PromptExperiment.Status.DRAFT, e.status());
    }

    @Test
    void rejectsEmptyVariants() {
        assertThrows(IllegalArgumentException.class, () -> new PromptExperiment(
                0L, 1L, "exp1", PromptExperiment.Status.DRAFT, List.of(), "au", 7L, null));
    }

    @Test
    void statusDefaultsToDraft() {
        PromptExperiment e = new PromptExperiment(
                0L, 1L, "exp", null,
                List.of(new PromptVariant(1L, 100, "only")),
                "au", 7L, null);
        assertEquals(PromptExperiment.Status.DRAFT, e.status());
        assertTrue(e.toMap().containsKey("variants"));
    }
}
