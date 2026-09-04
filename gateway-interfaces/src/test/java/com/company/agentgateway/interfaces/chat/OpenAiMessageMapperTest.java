package com.company.agentgateway.interfaces.chat;

import com.company.agentgateway.interfaces.chat.OpenAiChatDto.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAiMessageMapper 单元测试：覆盖 messages→prompt 压平 + completionId 生成 + token 估算。
 */
class OpenAiMessageMapperTest {

    @Test
    void flatten_消息为null抛IllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> OpenAiMessageMapper.flatten(null));
    }

    @Test
    void flatten_空列表抛IllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> OpenAiMessageMapper.flatten(List.of()));
    }

    @Test
    void flatten_单条user消息直接返回content不加前缀() {
        String result = OpenAiMessageMapper.flatten(List.of(new Message("user", "hi")));
        assertEquals("hi", result);
    }

    @Test
    void flatten_多轮消息含system与user且顺序保持() {
        List<Message> msgs = List.of(
                new Message("system", "You are a helpful assistant."),
                new Message("user", "What is 2+2?"),
                new Message("assistant", "It is 4."),
                new Message("user", "And 3+3?"));
        String result = OpenAiMessageMapper.flatten(msgs);
        assertTrue(result.contains("System: You are a helpful assistant."), "应包含 system 段及前缀");
        assertTrue(result.contains("User: What is 2+2?"), "应包含首条 user 段及前缀");
        assertTrue(result.contains("Assistant: It is 4."), "应包含 assistant 段及前缀");
        assertTrue(result.contains("User: And 3+3?"), "应包含第二条 user 段及前缀");
        // 顺序断言：system 段在第一条 user 段之前，assistant 段夹在两条 user 段之间
        int idxSystem = result.indexOf("System:");
        int idxUser1 = result.indexOf("User:");
        int idxAssistant = result.indexOf("Assistant:");
        int idxUser2 = result.indexOf("And 3+3?");
        assertTrue(idxSystem < idxUser1, "System 段应在首条 user 段之前");
        assertTrue(idxUser1 < idxAssistant, "首条 user 应在 assistant 之前");
        assertTrue(idxAssistant < idxUser2, "assistant 段应在第二条 user 之前");
        // 段落间用 \n\n 分隔
        assertTrue(result.contains("\n\n"), "段落之间应使用 \\n\\n 分隔");
    }

    @Test
    void flatten_content为null的message被跳过不产生字面量null() {
        List<Message> msgs = List.of(
                new Message("user", null),
                new Message("user", "hello"));
        String result = OpenAiMessageMapper.flatten(msgs);
        assertFalse(result.contains("null"), "content 为 null 不应产生字面量 null");
        assertTrue(result.contains("hello"), "应有有效 user 段");
    }

    @Test
    void newCompletionId_以chatcmpl前缀开头() {
        String id = OpenAiMessageMapper.newCompletionId();
        assertNotNull(id);
        assertTrue(id.startsWith("chatcmpl-"), "id 应以 chatcmpl- 开头");
        // 24 位 base36 后缀（去掉前缀长度 9 后剩 24）
        assertEquals("chatcmpl-".length() + 24, id.length(), "后缀应为 24 位");
    }

    @Test
    void newCompletionId_两次调用id不相等() {
        String id1 = OpenAiMessageMapper.newCompletionId();
        String id2 = OpenAiMessageMapper.newCompletionId();
        assertNotEquals(id1, id2, "连续调用应生成不同的 id");
    }

    @Test
    void estimateTokens_空字符串返回0() {
        assertEquals(0, OpenAiMessageMapper.estimateTokens(""));
    }

    @Test
    void estimateTokens_chars除以4() {
        assertEquals(1, OpenAiMessageMapper.estimateTokens("abcd")); // 4/4=1
        assertEquals(4, OpenAiMessageMapper.estimateTokens("a".repeat(16))); // 16/4=4
    }
}