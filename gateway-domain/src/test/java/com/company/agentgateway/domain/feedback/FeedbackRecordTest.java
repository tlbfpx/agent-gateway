package com.company.agentgateway.domain.feedback;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackRecordTest {

    @Test
    void create_setsIdZeroAndNowCreatedAt() {
        Instant before = Instant.now();
        FeedbackRecord r = FeedbackRecord.create(
                "trace-1", "span-1", "au", "user-1",
                "gpt-4o", FeedbackSentiment.POSITIVE, 5,
                "great", List.of("good_explanation"), Map.of("source", "chat"));
        Instant after = Instant.now();
        assertEquals(0L, r.id());
        assertNotNull(r.createdAt());
        assertTrue(!r.createdAt().isBefore(before) && !r.createdAt().isAfter(after));
        assertEquals(List.of("good_explanation"), r.tags());
        assertEquals(Map.of("source", "chat"), r.metadata());
    }

    @Test
    void rejectsBlankTraceIdAndTenant() {
        assertThrows(IllegalArgumentException.class, () -> FeedbackRecord.create(
                "", null, "au", null, null, FeedbackSentiment.POSITIVE,
                null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> FeedbackRecord.create(
                "trace-1", null, "", null, null, FeedbackSentiment.POSITIVE,
                null, null, null, null));
    }

    @Test
    void rejectsNullSentiment() {
        assertThrows(IllegalArgumentException.class, () -> FeedbackRecord.create(
                "trace-1", null, "au", null, null, null,
                null, null, null, null));
    }

    @Test
    void rejectsScoreOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> FeedbackRecord.create(
                "trace-1", null, "au", null, null, FeedbackSentiment.POSITIVE,
                0, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> FeedbackRecord.create(
                "trace-1", null, "au", null, null, FeedbackSentiment.POSITIVE,
                6, null, null, null));
    }

    @Test
    void truncatesCommentOver500() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) sb.append('a');
        FeedbackRecord r = FeedbackRecord.create(
                "trace-1", null, "au", null, null, FeedbackSentiment.POSITIVE,
                null, sb.toString(), null, null);
        assertEquals(500, r.comment().length());
    }

    @Test
    void toMap_producesFlatShape() {
        FeedbackRecord r = new FeedbackRecord(
                42L, "trace-1", "span-1", "au", "user-1", "gpt-4o",
                FeedbackSentiment.NEGATIVE, 2, "wrong",
                List.of("hallucination"), Map.of("k", "v"),
                Instant.parse("2026-09-01T10:00:00Z"));
        Map<String, Object> m = r.toMap();
        assertEquals(42L, m.get("id"));
        assertEquals("trace-1", m.get("traceId"));
        assertEquals("NEGATIVE", m.get("sentiment"));
        assertEquals(2, m.get("score"));
        assertEquals("hallucination", ((List<?>) m.get("tags")).get(0));
        assertEquals("2026-09-01T10:00:00Z", m.get("createdAt"));
    }
}
