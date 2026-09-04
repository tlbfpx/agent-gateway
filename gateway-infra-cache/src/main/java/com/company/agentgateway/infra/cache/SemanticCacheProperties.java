package com.company.agentgateway.infra.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 语义缓存配置(Sprint 4 P0),绑定 application.yml 的 {@code gateway.cache.*}。
 */
@ConfigurationProperties(prefix = "gateway.cache")
public class SemanticCacheProperties {

    /** 总开关(默认关闭,避免无 Key 时启动失败)。 */
    private boolean enabled = false;

    /** cosine 相似度阈值,默认 0.92(保守,降低错命中)。 */
    private double similarityThreshold = 0.92;

    /** 命中缓存项保留时长,默认 7 天。 */
    private Duration ttl = Duration.ofDays(7);

    /** 写入抖动比例(避免雪崩),默认 ±10%。 */
    private double jitterRatio = 0.1;

    /** 最小响应长度(过短不缓存),默认 64 字节。 */
    private int minResponseLength = 64;

    /** 最小响应 token 数,默认 8(过短不值得缓存)。 */
    private int minResponseTokens = 8;

    /** L1 Caffeine 最大条目数。 */
    private int l1MaxSize = 10_000;

    /** L1 Caffeine 过期时间(亚毫秒精确路径)。 */
    private Duration l1Ttl = Duration.ofMinutes(5);

    /** ANN top-K。 */
    private int annTopK = 5;

    /** OpenAI 兼容端点 base URL(默认官方)。 */
    private String embeddingBaseUrl;

    /** OpenAI API key(若为空则缓存禁用)。 */
    private String embeddingApiKey;

    /** Embedding 模型(默认 text-embedding-3-small)。 */
    private String embeddingModel = "text-embedding-3-small";

    /** Embedding 维度(默认 1536)。 */
    private int embeddingDimensions = 1536;

    // ─── getters / setters ───

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public double getSimilarityThreshold() { return similarityThreshold; }
    public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }

    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }

    public double getJitterRatio() { return jitterRatio; }
    public void setJitterRatio(double jitterRatio) { this.jitterRatio = jitterRatio; }

    public int getMinResponseLength() { return minResponseLength; }
    public void setMinResponseLength(int minResponseLength) { this.minResponseLength = minResponseLength; }

    public int getMinResponseTokens() { return minResponseTokens; }
    public void setMinResponseTokens(int minResponseTokens) { this.minResponseTokens = minResponseTokens; }

    public int getL1MaxSize() { return l1MaxSize; }
    public void setL1MaxSize(int l1MaxSize) { this.l1MaxSize = l1MaxSize; }

    public Duration getL1Ttl() { return l1Ttl; }
    public void setL1Ttl(Duration l1Ttl) { this.l1Ttl = l1Ttl; }

    public int getAnnTopK() { return annTopK; }
    public void setAnnTopK(int annTopK) { this.annTopK = annTopK; }

    public String getEmbeddingBaseUrl() { return embeddingBaseUrl; }
    public void setEmbeddingBaseUrl(String embeddingBaseUrl) { this.embeddingBaseUrl = embeddingBaseUrl; }

    public String getEmbeddingApiKey() { return embeddingApiKey; }
    public void setEmbeddingApiKey(String embeddingApiKey) { this.embeddingApiKey = embeddingApiKey; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public int getEmbeddingDimensions() { return embeddingDimensions; }
    public void setEmbeddingDimensions(int embeddingDimensions) { this.embeddingDimensions = embeddingDimensions; }
}