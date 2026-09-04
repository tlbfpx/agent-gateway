package com.company.agentgateway.interfaces.feedback;

import com.company.agentgateway.application.feedback.FeedbackService;
import com.company.agentgateway.domain.feedback.FeedbackRecord;
import com.company.agentgateway.domain.feedback.FeedbackRepository.FeedbackQuery;
import com.company.agentgateway.domain.feedback.FeedbackRepository.Summary;
import com.company.agentgateway.domain.feedback.FeedbackSentiment;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feedback 标注端点（spec 2026-09-01 §feedback-annotation §5）。
 *
 * <ul>
 *   <li>{@code POST /v1/feedback} —— 用户/SDK 提交标注（X-API-Key 鉴权）</li>
 *   <li>{@code GET /v1/feedback} —— 管理端条件查询（X-Admin-Token）</li>
 *   <li>{@code GET /v1/feedback/summary} —— 聚合统计（X-Admin-Token）</li>
 *   <li>{@code GET /v1/feedback/by-trace/{traceId}} —— 按 trace 查全部</li>
 * </ul>
 *
 * <p>认证策略与 AdminAudit 对齐：写用 X-API-Key（chat 链路），管理查询用 X-Admin-Token。
 */
@RestController
@RequestMapping("/v1/feedback")
public class FeedbackController {

    private final FeedbackService service;

    public FeedbackController(FeedbackService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> record(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestBody Map<String, Object> body) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-API-Key required");
        }
        String tenantId = stringOrThrow(body, "tenantId", "tenantId required");
        String traceId = stringOrThrow(body, "traceId", "traceId required");
        String spanId = stringOrNull(body, "spanId");
        String userId = stringOrNull(body, "userId");
        String model = stringOrNull(body, "model");
        String sentimentRaw = stringOrThrow(body, "sentiment", "sentiment required");
        FeedbackSentiment sentiment;
        try {
            sentiment = FeedbackSentiment.parse(sentimentRaw);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid_sentiment: " + ex.getMessage());
        }
        Integer score = intOrNull(body, "score");
        if (score != null && (score < 1 || score > 5)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid_score: score must be 1..5, got " + score);
        }
        String comment = stringOrNull(body, "comment");
        List<String> tags = stringListOrNull(body, "tags");
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) body.get("metadata");

        FeedbackRecord persisted = service.record(
                tenantId, traceId, spanId, userId, model, sentiment, score,
                comment, tags, metadata);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", persisted.id());
        resp.put("createdAt", persisted.createdAt().toString());
        resp.put("sentiment", persisted.sentiment().name());
        resp.put("traceId", persisted.traceId());
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping
    public List<Map<String, Object>> query(
            @RequestHeader("X-Admin-Token") String adminToken,
            @RequestParam(defaultValue = "au") String tenant,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String sentiment,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        if (adminToken == null || adminToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Admin-Token required");
        }
        FeedbackSentiment s = parseSentimentOrNull(sentiment);
        Instant fromI = parseInstant(from, "from");
        Instant toI = parseInstant(to, "to");
        if (limit < 0 || offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit/offset must be >= 0");
        }
        FeedbackQuery q = new FeedbackQuery(tenant, traceId, userId, model, s, fromI, toI, limit, offset);
        return service.query(q).stream().map(FeedbackRecord::toMap).toList();
    }

    @GetMapping("/by-trace/{traceId}")
    public List<Map<String, Object>> findByTraceId(
            @RequestHeader("X-Admin-Token") String adminToken,
            @PathVariable String traceId) {
        if (adminToken == null || adminToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Admin-Token required");
        }
        return service.findByTraceId(traceId).stream().map(FeedbackRecord::toMap).toList();
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(
            @RequestHeader("X-Admin-Token") String adminToken,
            @RequestParam(defaultValue = "au") String tenant,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        if (adminToken == null || adminToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Admin-Token required");
        }
        Instant fromI = parseInstant(from, "from");
        Instant toI = parseInstant(to, "to");
        FeedbackQuery q = new FeedbackQuery(tenant, null, null, model, null, fromI, toI, 500, 0);
        Summary s = service.summarize(q);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", s.total());
        out.put("positive", s.positive());
        out.put("negative", s.negative());
        out.put("neutral", s.neutral());
        out.put("positiveRatio", s.positiveRatio());
        out.put("withComment", s.withComment());
        out.put("byModel", s.byModel());
        out.put("topTags", s.topTags());
        return out;
    }

    private static String stringOrThrow(Map<String, Object> body, String key, String message) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return v.toString();
    }

    private static String stringOrNull(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringListOrNull(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        if (v instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return null;
    }

    private static Integer intOrNull(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid " + key + ": must be integer");
        }
    }

    private static FeedbackSentiment parseSentimentOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return FeedbackSentiment.parse(raw);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid sentiment: " + ex.getMessage());
        }
    }

    private static Instant parseInstant(String value, String name) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid " + name + ": must be ISO-8601 instant");
        }
    }
}
