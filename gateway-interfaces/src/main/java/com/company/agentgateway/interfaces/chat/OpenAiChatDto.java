package com.company.agentgateway.interfaces.chat;

import java.util.List;

/**
 * OpenAI 兼容的 wire-format DTO 集合（spec §1 — 产品 #1 OpenAI-compatible passthrough）。
 *
 * <p>字段名刻意保持与 OpenAI 官方 API 一致（snake_case），由 Jackson 默认策略直接序列化/反序列化；
 * 不要改成驼峰，也不需要 @JsonProperty 别名 —— 改了反而会让 OpenAI SDK 解析失败。
 *
 * <p>本类为纯数据 record，不依赖 Spring/框架，便于单元测试直接驱动。
 */
public final class OpenAiChatDto {

    private OpenAiChatDto() {
        // 工具类，禁止实例化
    }

    /**
     * 请求体 {@code POST /v1/chat/completions}。
     *
     * @param model       模型 id
     * @param messages    对话历史；空/缺失将触发 400 invalid_messages
     * @param stream      是否流式；缺省按 false 处理（Boolean 包装类型即可为 null）
     * @param temperature 采样温度（透传，未消费）
     * @param max_tokens  最大输出 token（透传，未消费）
     * @param user        调用方标识（透传，未消费）
     */
    public record ChatCompletionRequest(
            String model,
            List<Message> messages,
            Boolean stream,
            Double temperature,
            Integer max_tokens,
            String user) {
    }

    /** 单条消息（system / user / assistant）。 */
    public record Message(String role, String content) {
    }

    /** 非流式响应体 {@code {"id":"chatcmpl-...","object":"chat.completion",...}}。 */
    public record ChatCompletionResponse(
            String id,
            String object,
            long created,
            String model,
            List<Choice> choices,
            Usage usage) {
    }

    public record Choice(int index, Message message, String finish_reason) {
    }

    public record Usage(int prompt_tokens, int completion_tokens, int total_tokens) {
    }

    /** 流式帧 {@code {"id":"chatcmpl-...","object":"chat.completion.chunk",...}}。 */
    public record ChunkResponse(
            String id,
            String object,
            long created,
            String model,
            List<ChunkChoice> choices) {
    }

    public record ChunkChoice(int index, Delta delta, String finish_reason) {
    }

    public record Delta(String role, String content) {
    }

    /** 错误体，对齐 OpenAI {@code {"error":{"message":...,"type":...,"code":...}}}。 */
    public record ErrorEnvelope(ErrorBody error) {
    }

    public record ErrorBody(String message, String type, String code) {
    }
}