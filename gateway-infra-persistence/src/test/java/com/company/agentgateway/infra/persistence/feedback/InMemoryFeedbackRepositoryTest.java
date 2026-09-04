package com.company.agentgateway.infra.persistence.feedback;

import com.company.agentgateway.domain.feedback.FeedbackRecord;
import com.company.agentgateway.domain.feedback.FeedbackRepository;
import com.company.agentgateway.domain.feedback.FeedbackSentiment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryFeedbackRepositoryTest {

    private FeedbackRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryFeedbackRepository();
    }

    @Test
    void save_assignsIncrementingIds() {
        long id1 = repo.save(FeedbackRecord.create(
                "t-1", null, "au", "u-1", "gpt-4o",
                FeedbackSentiment.POSITIVE, 5, "great", null, null));
        long id2 = repo.save(FeedbackRecord.create(
                "t-2", null, "au", "u-2", "gpt-4o",
                FeedbackSentiment.NEGATIVE, 2, "wrong", null, null));
        assertEquals(id1 + 1, id2);
        assertEquals(1L, repo.findById(id1).get().id());
        assertEquals("gpt-4o", repo.findById(id2).get().model());
    }

    @Test
    void findByTraceId_returnsNewestFirst() {
        long a = repo.save(FeedbackRecord.create("t-1", null, "au", null, null,
                FeedbackSentiment.POSITIVE, null, null, null, null));
        long b = repo.save(FeedbackRecord.create("t-1", null, "au", null, null,
                FeedbackSentiment.NEGATIVE, null, null, null, null));
        List<FeedbackRecord> got = repo.findByTraceId("t-1");
        assertEquals(2, got.size());
        assertEquals(b, got.get(0).id());
        assertEquals(a, got.get(1).id());
    }

    @Test
    void query_filtersByModelAndSentiment() {
        repo.save(FeedbackRecord.create("t-1", null, "au", null, "gpt-4o",
                FeedbackSentiment.POSITIVE, null, null, null, null));
        repo.save(FeedbackRecord.create("t-2", null, "au", null, "gpt-4o",
                FeedbackSentiment.NEGATIVE, null, null, null, null));
        repo.save(FeedbackRecord.create("t-3", null, "au", null, "claude",
                FeedbackSentiment.POSITIVE, null, null, null, null));

        List<FeedbackRecord> neg4o = repo.query(new FeedbackRepository.FeedbackQuery(
                "au", null, null, "gpt-4o", FeedbackSentiment.NEGATIVE,
                null, null, 50, 0));
        assertEquals(1, neg4o.size());
        assertEquals("t-2", neg4o.get(0).traceId());
    }

    @Test
    void aggregate_countsBySentimentAndModel() {
        repo.save(FeedbackRecord.create("t-1", null, "au", null, "gpt-4o",
                FeedbackSentiment.POSITIVE, null, null, List.of("good"), null));
        repo.save(FeedbackRecord.create("t-2", null, "au", null, "gpt-4o",
                FeedbackSentiment.POSITIVE, null, "great", List.of("good"), null));
        repo.save(FeedbackRecord.create("t-3", null, "au", null, "gpt-4o",
                FeedbackSentiment.NEGATIVE, null, "wrong", List.of("hallucination"), null));
        repo.save(FeedbackRecord.create("t-4", null, "au", null, "claude",
                FeedbackSentiment.NEUTRAL, null, null, List.of("good"), null));

        FeedbackRepository.FeedbackQuery q = new FeedbackRepository.FeedbackQuery(
                "au", null, null, null, null, null, null, 500, 0);
        FeedbackRepository.Summary s = repo.aggregate(q);
        assertEquals(4, s.total());
        assertEquals(2, s.positive());
        assertEquals(1, s.negative());
        assertEquals(1, s.neutral());
        assertEquals(0.5, s.positiveRatio(), 0.001);
        assertEquals(2, s.withComment());
        assertEquals(2, s.byModel().size());
        assertEquals("gpt-4o", s.byModel().get(0).model());
        assertEquals(3, s.byModel().get(0).count());
        assertEquals(2, s.topTags().size());
        assertEquals("good", s.topTags().get(0).tag());
        assertEquals(3, s.topTags().get(0).count());
    }

    @Test
    void deleteById_removesMatching() {
        long id = repo.save(FeedbackRecord.create("t-1", null, "au", null, null,
                FeedbackSentiment.POSITIVE, null, null, null, null));
        assertEquals(1, repo.deleteById(id));
        assertTrue(repo.findById(id).isEmpty());
        assertEquals(0, repo.deleteById(99999));
    }
}
