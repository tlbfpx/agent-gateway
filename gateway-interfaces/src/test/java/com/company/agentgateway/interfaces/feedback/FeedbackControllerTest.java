package com.company.agentgateway.interfaces.feedback;

import com.company.agentgateway.application.feedback.FeedbackService;
import com.company.agentgateway.domain.feedback.FeedbackRecord;
import com.company.agentgateway.domain.feedback.FeedbackSentiment;
import com.company.agentgateway.infra.persistence.feedback.InMemoryFeedbackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeedbackControllerTest {

    private FeedbackController controller;

    @BeforeEach
    void setUp() {
        controller = new FeedbackController(new FeedbackService(new InMemoryFeedbackRepository()));
    }

    @Test
    void record_returns201WithId() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", "au");
        body.put("traceId", "tr-1");
        body.put("sentiment", "thumbs_up");
        body.put("score", 5);
        body.put("comment", "great");
        body.put("tags", List.of("good"));
        var resp = controller.record("sk-test", body);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertNotNull(resp.getBody().get("id"));
        assertEquals("POSITIVE", resp.getBody().get("sentiment"));
    }

    @Test
    void record_acceptsEmojiAndAlias() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", "au");
        body.put("traceId", "tr-1");
        body.put("sentiment", "👎");
        body.put("tags", List.of("hallucination"));
        var resp = controller.record("sk-test", body);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertEquals("NEGATIVE", resp.getBody().get("sentiment"));
    }

    @Test
    void record_rejectsInvalidScore() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", "au");
        body.put("traceId", "tr-1");
        body.put("sentiment", "positive");
        body.put("score", 7);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.record("sk-test", body));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertNotNull(ex.getReason());
        assert ex.getReason().contains("invalid_score");
    }

    @Test
    void record_rejectsInvalidSentiment() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", "au");
        body.put("traceId", "tr-1");
        body.put("sentiment", "love-hate");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.record("sk-test", body));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assert ex.getReason().contains("invalid_sentiment");
    }

    @Test
    void record_rejectsBlankTraceId() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", "au");
        body.put("traceId", "");
        body.put("sentiment", "positive");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.record("sk-test", body));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void record_rejectsMissingApiKey() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", "au");
        body.put("traceId", "tr-1");
        body.put("sentiment", "positive");
        assertThrows(ResponseStatusException.class, () -> controller.record(null, body));
        assertThrows(ResponseStatusException.class, () -> controller.record("", body));
    }

    @Test
    void query_requiresAdminToken() {
        assertThrows(ResponseStatusException.class,
                () -> controller.query("", "au", null, null, null, null, null, null, 50, 0));
    }

    @Test
    void query_returnsListAfterRecord() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", "au");
        body.put("traceId", "tr-1");
        body.put("sentiment", "positive");
        controller.record("sk", body);

        List<Map<String, Object>> got = controller.query(
                "admin-tok", "au", null, null, null, null, null, null, 50, 0);
        assertEquals(1, got.size());
        assertEquals("tr-1", got.get(0).get("traceId"));
    }

    @Test
    void findByTraceId_filters() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", "au");
        body.put("traceId", "tr-1");
        body.put("sentiment", "positive");
        controller.record("sk", body);
        body.put("traceId", "tr-2");
        controller.record("sk", body);

        List<Map<String, Object>> got = controller.findByTraceId("admin-tok", "tr-1");
        assertEquals(1, got.size());
        assertEquals("tr-1", got.get(0).get("traceId"));
    }

    @Test
    void summary_returnsAggregate() {
        controller.record("sk", bodyOf("au", "tr-1", "positive"));
        controller.record("sk", bodyOf("au", "tr-2", "negative"));
        controller.record("sk", bodyOf("au", "tr-3", "thumbs_up"));

        Map<String, Object> got = controller.summary("admin-tok", "au", null, null, null);
        assertEquals(3, ((Number) got.get("total")).intValue());
        assertEquals(2, ((Number) got.get("positive")).intValue());
        assertEquals(1, ((Number) got.get("negative")).intValue());
    }

    private static Map<String, Object> bodyOf(String tenant, String trace, String sentiment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenant);
        body.put("traceId", trace);
        body.put("sentiment", sentiment);
        return body;
    }
}
