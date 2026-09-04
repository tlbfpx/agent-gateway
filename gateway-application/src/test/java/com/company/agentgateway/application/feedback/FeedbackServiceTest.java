package com.company.agentgateway.application.feedback;

import com.company.agentgateway.domain.feedback.FeedbackRecord;
import com.company.agentgateway.domain.feedback.FeedbackRepository;
import com.company.agentgateway.domain.feedback.FeedbackRepository.FeedbackQuery;
import com.company.agentgateway.domain.feedback.FeedbackRepository.Summary;
import com.company.agentgateway.domain.feedback.FeedbackSentiment;
import com.company.agentgateway.infra.persistence.feedback.InMemoryFeedbackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackServiceTest {

    private FeedbackRepository repo;
    private FeedbackService service;

    @BeforeEach
    void setUp() {
        repo = new InMemoryFeedbackRepository();
        service = new FeedbackService(repo);
    }

    @Test
    void record_assignsIdAndReturnsPersisted() {
        FeedbackRecord r = service.record("au", "trace-1", "span-1", "user-1",
                "gpt-4o", FeedbackSentiment.POSITIVE, 5, "great",
                List.of("good"), Map.of("source", "chat"));
        assertTrue(r.id() > 0);
        assertEquals("trace-1", r.traceId());
        assertEquals(FeedbackSentiment.POSITIVE, r.sentiment());
        assertEquals(5, r.score());
        assertNotNull(r.createdAt());
    }

    @Test
    void record_truncatesLongComment() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) sb.append('x');
        FeedbackRecord r = service.record("au", "trace-1", null, null,
                null, FeedbackSentiment.POSITIVE, null, sb.toString(), null, null);
        assertEquals(500, r.comment().length());
    }

    @Test
    void query_filtersBySentiment() {
        service.record("au", "t-1", null, null, null,
                FeedbackSentiment.POSITIVE, null, null, null, null);
        service.record("au", "t-2", null, null, null,
                FeedbackSentiment.NEGATIVE, null, null, null, null);

        List<FeedbackRecord> neg = service.query(new FeedbackQuery(
                "au", null, null, null, FeedbackSentiment.NEGATIVE, null, null, 50, 0));
        assertEquals(1, neg.size());
        assertEquals("t-2", neg.get(0).traceId());
    }

    @Test
    void findByTraceId_returnsInOrder() {
        service.record("au", "t-1", null, null, null,
                FeedbackSentiment.POSITIVE, null, null, null, null);
        service.record("au", "t-1", null, null, null,
                FeedbackSentiment.NEGATIVE, null, null, null, null);
        List<FeedbackRecord> got = service.findByTraceId("t-1");
        assertEquals(2, got.size());
    }

    @Test
    void summarize_returnsAggregate() {
        service.record("au", "t-1", null, null, "gpt-4o",
                FeedbackSentiment.POSITIVE, null, "good", List.of("good"), null);
        service.record("au", "t-2", null, null, "gpt-4o",
                FeedbackSentiment.NEGATIVE, null, "bad", List.of("hallucination"), null);
        Summary s = service.summarize(new FeedbackQuery(
                "au", null, null, null, null, null, null, 500, 0));
        assertEquals(2, s.total());
        assertEquals(1, s.positive());
        assertEquals(1, s.negative());
        assertEquals(2, s.topTags().size());
    }
}
