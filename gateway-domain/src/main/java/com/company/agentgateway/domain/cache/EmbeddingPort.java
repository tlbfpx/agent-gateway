package com.company.agentgateway.domain.cache;

import java.util.List;

/**
 * Embedding 出站端口(Sprint 4 P0):
 * 把文本转成 dense vector。domain 抽象,infra-cache 实现。
 *
 * <h2>P0 默认实现</h2>
 * <ul>
 *   <li>{@code OpenAiEmbeddingClient}: text-embedding-3-small(1536 维,$0.02/M tokens)</li>
 *   <li>二期备选:本地 bge-m3(敏感场景)</li>
 * </ul>
 */
public interface EmbeddingPort {

    /**
     * 单条 query embedding。
     *
     * @param text 待编码文本;已 normalized(去标点/小写)
     * @return 1536 维 float 数组(实际维度由 model 决定)
     * @throws PiiRefusedException 输入含 PII 时抛出(避免 PII 落到 embedding 服务)
     * @throws EmbeddingException 上游失败 / 超时 / 4xx 5xx
     */
    float[] embed(String text);

    /**
     * 批量 embedding(节省 round trip;单批 ≤ 96 条)。
     */
    List<float[]> embedBatch(List<String> texts);

    /** 当前模型名(用于 metadata 记录)。 */
    String modelName();

    /** Embedding 维度。 */
    int dimensions();

    /** 文本检测到 PII 时抛出(上层应直接 SKIP 缓存)。 */
    class PiiRefusedException extends RuntimeException {
        public PiiRefusedException(String msg) { super(msg); }
    }

    /** 上游失败(网络/超时/4xx/5xx)。 */
    class EmbeddingException extends RuntimeException {
        public EmbeddingException(String msg) { super(msg); }
        public EmbeddingException(String msg, Throwable cause) { super(msg, cause); }
    }
}