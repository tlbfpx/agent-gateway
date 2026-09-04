package com.company.agentgateway.interfaces.chat;

import com.company.agentgateway.interfaces.chat.OpenAiChatDto.Message;

import java.security.SecureRandom;
import java.util.List;

/**
 * OpenAI 兼容格式的辅助工具：messages 数组 → 单 prompt 字符串压平 + completionId 生成 + token 估算。
 *
 * <p>纯函数/静态方法，不依赖 Spring，便于单元测试直接驱动。
 */
public final class OpenAiMessageMapper {

    /** 上限：与 ChatController.requireValidPrompt 一致，避免超大 prompt 打爆 token 预算。 */
    public static final int MAX_PROMPT_LENGTH = 32_768;

    /** 角色前缀：system/user/assistant。 */
    private static final String PREFIX_SYSTEM = "System: ";
    private static final String PREFIX_USER = "User: ";
    private static final String PREFIX_ASSISTANT = "Assistant: ";

    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    private OpenAiMessageMapper() {
        // 工具类，禁止实例化
    }

    /**
     * messages → 单 prompt。
     *
     * <p>规则：
     * <ul>
     *   <li>null 或空列表 → 抛 IllegalArgumentException（控制器捕获后转 400 invalid_messages）。</li>
     *   <li>单条 user 消息 → 直接返回 content，无任何前缀，保证最常见的单轮调用行为与 /v1/chat 完全一致。</li>
     *   <li>其他情况 → 按顺序拼接，system/user/assistant 加角色前缀，段落间用 {@code \n\n} 分隔。</li>
     *   <li>content 为 null 的 message 跳过（不产生字面量 "null"）。</li>
     * </ul>
     */
    public static String flatten(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages is required and must not be empty");
        }
        // 单条 user 消息：保持与 /v1/chat 行为完全一致（无前缀、无分隔）
        if (messages.size() == 1) {
            Message single = messages.get(0);
            if ("user".equals(single.role()) && single.content() != null) {
                return single.content();
            }
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Message m : messages) {
            if (m == null || m.content() == null) {
                // 跳过 null message 与 content 为 null 的项，不产生字面量
                continue;
            }
            if (!first) {
                sb.append("\n\n");
            }
            first = false;
            String prefix = switch (m.role() == null ? "" : m.role()) {
                case "system" -> PREFIX_SYSTEM;
                case "user" -> PREFIX_USER;
                case "assistant" -> PREFIX_ASSISTANT;
                default -> "";
            };
            sb.append(prefix).append(m.content());
        }
        return sb.toString();
    }

    /**
     * 生成 OpenAI 形态的 completion id：{@code chatcmpl-} + 24 位随机 base36。
     *
     * <p>不可用作加密安全用途，但碰撞概率对单租户流量足够低（36^24 量级）。
     */
    public static String newCompletionId() {
        StringBuilder sb = new StringBuilder("chatcmpl-");
        for (int i = 0; i < 24; i++) {
            sb.append(BASE36[RNG.nextInt(BASE36.length)]);
        }
        return sb.toString();
    }

    /**
     * token 估算（chars/4，与 ChatStreamEvent.Meta 一致）。
     *
     * <p>当 orchestrator 没回 Meta 时用于兜底填充 usage，保证非流式响应仍能返回有效 usage。
     */
    public static int estimateTokens(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        return s.length() / 4;
    }
}