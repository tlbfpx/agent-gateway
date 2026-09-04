package com.company.agentgateway.infra.cache;

import com.company.agentgateway.domain.cache.EmbeddingPort;
import com.company.agentgateway.domain.cache.PiiDetector;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI text-embedding-3-small Embedding 客户端(Sprint 4 P0)。
 *
 * <h2>关键设计</h2>
 * <ul>
 *   <li>维度 1536(Sprint 4 默认);后续可切 text-embedding-3-large(3072 维)</li>
 *   <li>失败分类:429/5xx → 重试 1 次(2s backoff);4xx → 立即抛 EmbeddingException</li>
 *   <li>PII 拦截:含身份证/银行卡/邮箱 等 → 抛 {@link EmbeddingPort.PiiRefusedException},
 *       上层应直接跳过缓存(避免 PII 落到第三方)</li>
 *   <li>批量:embedBatch 单次 ≤ 96 条(OpenAI 限制)</li>
 *   <li>超时:connect 5s, response 30s(LLM 嵌入一般 < 1s,30s 缓冲网络抖动)</li>
 * </ul>
 */
public class OpenAiEmbeddingClient implements EmbeddingPort {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingClient.class);
    public static final String DEFAULT_MODEL = "text-embedding-3-small";
    public static final int DEFAULT_DIMENSIONS = 1536;
    private static final int MAX_BATCH = 96;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final String modelName;
    private final int dimensions;

    public OpenAiEmbeddingClient(String apiKey, String baseUrl, String modelName, int dimensions) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com" : baseUrl;
        this.modelName = modelName == null || modelName.isBlank() ? DEFAULT_MODEL : modelName;
        this.dimensions = dimensions <= 0 ? DEFAULT_DIMENSIONS : dimensions;
        this.webClient = WebClient.builder()
                .baseUrl(this.baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new EmbeddingPort.EmbeddingException("text is blank");
        }
        if (PiiDetector.containsPii(text)) {
            throw new EmbeddingPort.PiiRefusedException("PII detected, refuse to embed");
        }
        EmbeddingRequest req = new EmbeddingRequest(modelName, List.of(text), dimensions);
        EmbeddingResponse resp = callWithRetry(req, 1);
        if (resp.data == null || resp.data.isEmpty()) {
            throw new EmbeddingPort.EmbeddingException("empty embedding response");
        }
        return resp.data.get(0).embedding;
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        for (String t : texts) {
            if (PiiDetector.containsPii(t)) {
                throw new EmbeddingPort.PiiRefusedException("PII detected in batch, refuse");
            }
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += MAX_BATCH) {
            List<String> batch = texts.subList(i, Math.min(i + MAX_BATCH, texts.size()));
            EmbeddingRequest req = new EmbeddingRequest(modelName, batch, dimensions);
            EmbeddingResponse resp = callWithRetry(req, 1);
            if (resp.data != null) {
                for (EmbeddingData d : resp.data) out.add(d.embedding);
            }
        }
        return out;
    }

    @Override
    public String modelName() { return modelName; }

    @Override
    public int dimensions() { return dimensions; }

    private EmbeddingResponse callWithRetry(EmbeddingRequest req, int maxAttempts) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return webClient.post()
                        .uri("/v1/embeddings")
                        .bodyValue(req)
                        .retrieve()
                        .bodyToMono(EmbeddingResponse.class)
                        .timeout(Duration.ofSeconds(30))
                        .onErrorResume(ex -> {
                            if (ex instanceof org.springframework.web.reactive.function.client.WebClientResponseException wcre) {
                                int code = wcre.getStatusCode().value();
                                if (code >= 400 && code < 500 && code != 429) {
                                    return Mono.error(new EmbeddingPort.EmbeddingException(
                                            "OpenAI 4xx: " + code + " " + wcre.getResponseBodyAsString(), wcre));
                                }
                                return Mono.error(new EmbeddingPort.EmbeddingException(
                                        "OpenAI 5xx/429: " + code, wcre));
                            }
                            return Mono.error(new EmbeddingPort.EmbeddingException("OpenAI call failed", ex));
                        })
                        .block();
            } catch (EmbeddingPort.EmbeddingException e) {
                last = e;
                // 仅 429/5xx 重试;4xx 直接抛
                if (e.getMessage() != null && (e.getMessage().contains("4xx") && !e.getMessage().contains("429"))) {
                    throw e;
                }
                if (attempt < maxAttempts) {
                    try { Thread.sleep(2000L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
                }
            }
        }
        throw last != null ? last : new EmbeddingPort.EmbeddingException("embedding failed after retries");
    }

    // ─── Wire formats ───

    record EmbeddingRequest(
            String model,
            List<String> input,
            @JsonProperty("dimensions") Integer dimensions) {}

    record EmbeddingResponse(String object, List<EmbeddingData> data, String model, Usage usage) {}

    record EmbeddingData(String object, int index, float[] embedding) {}

    record Usage(@JsonProperty("prompt_tokens") int promptTokens,
                 @JsonProperty("total_tokens") int totalTokens) {}

    /** unused — 留给未来的 batch retry/streaming 扩展 */
    @SuppressWarnings("unused")
    private static Flux<EmbeddingData> streamResponse(WebClient client) {
        return Flux.empty();
    }
}