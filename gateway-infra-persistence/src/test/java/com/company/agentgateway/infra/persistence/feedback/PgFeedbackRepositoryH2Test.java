package com.company.agentgateway.infra.persistence.feedback;

import com.company.agentgateway.domain.feedback.FeedbackRecord;
import com.company.agentgateway.domain.feedback.FeedbackRepository;
import com.company.agentgateway.domain.feedback.FeedbackRepository.FeedbackQuery;
import com.company.agentgateway.domain.feedback.FeedbackSentiment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2 内存数据库跑 Pg 实现(SQL 方言:PostgreSQL 兼容模式)。
 */
class PgFeedbackRepositoryH2Test {

    private PgFeedbackRepository repo;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:feedback_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE feedback (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    trace_id VARCHAR(255) NOT NULL,
                    span_id VARCHAR(255),
                    tenant_id VARCHAR(64) NOT NULL,
                    user_id VARCHAR(255),
                    model VARCHAR(128),
                    sentiment VARCHAR(16) NOT NULL,
                    score INT,
                    comment CLOB,
                    tags CLOB,
                    metadata CLOB,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
        repo = new PgFeedbackRepository(jdbc);
    }

    private FeedbackRecord make(String traceId, String sentiment) {
        return FeedbackRecord.create(
                traceId, "span-1", "au", "alice@x.com", "gpt-4o",
                FeedbackSentiment.parse(sentiment), 5, "great",
                List.of("good"), Map.of("source", "chat"));
    }

    @Test
    void saveAndFindById_roundTrip() {
        long id = repo.save(make("tr-1", "POSITIVE"));
        FeedbackRecord got = repo.findById(id).orElseThrow();
        assertEquals("tr-1", got.traceId());
        assertEquals(FeedbackSentiment.POSITIVE, got.sentiment());
        assertEquals("alice@x.com", got.userId());
        assertEquals(List.of("good"), got.tags());
    }

    @Test
    void findByTraceId_returnsNewestFirst() {
        repo.save(make("tr-1", "POSITIVE"));
        repo.save(make("tr-1", "NEGATIVE"));
        repo.save(make("tr-2", "POSITIVE"));
        List<FeedbackRecord> got = repo.findByTraceId("tr-1");
        assertEquals(2, got.size());
        // Newest first (按 id desc)
        assertTrue(got.get(0).id() > got.get(1).id());
    }

    @Test
    void query_filtersBySentiment() {
        repo.save(make("tr-1", "POSITIVE"));
        repo.save(make("tr-2", "POSITIVE"));
        repo.save(make("tr-3", "NEGATIVE"));

        List<FeedbackRecord> got = repo.query(new FeedbackRepository.FeedbackQuery(
                "au", null, null, null, FeedbackSentiment.POSITIVE, null, null, 50, 0));
        assertEquals(2, got.size());
    }

    @Test
    void delete_removes() {
        long id = repo.save(make("tr-1", "POSITIVE"));
        assertEquals(1, repo.deleteById(id));
        assertFalse(repo.findById(id).isPresent());
    }

    @Test
    void aggregate_countsBySentiment() {
        repo.save(make("tr-1", "POSITIVE"));
        repo.save(make("tr-2", "POSITIVE"));
        repo.save(make("tr-3", "NEGATIVE"));

        FeedbackRepository.Summary s = repo.aggregate(new FeedbackRepository.FeedbackQuery(
                "au", null, null, null, null, null, null, 50, 0));
        assertEquals(3, s.total());
        assertEquals(2, s.positive());
        assertEquals(1, s.negative());
    }

    @Test
    void save_update_existingRecord() {
        long id = repo.save(make("tr-1", "POSITIVE"));
        FeedbackRecord original = repo.findById(id).orElseThrow();
        FeedbackRecord updated = new FeedbackRecord(
                original.id(), original.traceId(), original.spanId(), original.tenantId(),
                original.userId(), original.model(), FeedbackSentiment.NEGATIVE, original.score(),
                original.comment(), original.tags(), original.metadata(),
                original.createdAt());
        repo.save(updated);
        FeedbackRecord reread = repo.findById(id).orElseThrow();
        assertEquals(FeedbackSentiment.NEGATIVE, reread.sentiment());
        assertNotNull(reread);
    }
}