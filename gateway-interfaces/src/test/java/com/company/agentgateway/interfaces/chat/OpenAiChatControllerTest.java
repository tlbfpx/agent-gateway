package com.company.agentgateway.interfaces.chat;

import com.company.agentgateway.application.orchestration.ChatOrchestrator;
import com.company.agentgateway.application.orchestration.ChatRequest;
import com.company.agentgateway.application.orchestration.ChatStreamEvent;
import com.company.agentgateway.infra.observability.trace.GatewayTracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OpenAiChatController 测试：覆盖 /v1/chat/completions（流式+非流式）、/v1/embeddings（501 stub）、
 * 鉴权/模型错误状态码翻译、Bearer 头透传、SSE 帧格式（不含 event: chunk）。
 */
class OpenAiChatControllerTest {

    private MockMvc mockMvc;
    private ChatOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = mock(ChatOrchestrator.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new OpenAiChatController(orchestrator, GatewayTracer.NOOP)).build();
    }

    @Test
    void 非流式_单条user消息_返回OpenAI结构() throws Exception {
        when(orchestrator.orchestrate(any(), anyString(), any())).thenReturn(
                Flux.just(
                        new ChatStreamEvent.Delta("Hello "),
                        new ChatStreamEvent.Delta("world"),
                        new ChatStreamEvent.Complete("Hello world")));

        mockMvc.perform(post("/v1/chat/completions")
                        .header("X-API-Key", "sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.choices[0].message.role").value("assistant"))
                .andExpect(jsonPath("$.choices[0].message.content").value("Hello world"))
                .andExpect(jsonPath("$.choices[0].finish_reason").value("stop"))
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("chatcmpl-")));
    }

    @Test
    void 非流式_多轮messages压平为prompt() throws Exception {
        when(orchestrator.orchestrate(any(), anyString(), any())).thenReturn(
                Flux.just(new ChatStreamEvent.Complete("ok")));

        mockMvc.perform(post("/v1/chat/completions")
                        .header("X-API-Key", "sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4o\",\"messages\":[" +
                                "{\"role\":\"system\",\"content\":\"be nice\"}," +
                                "{\"role\":\"user\",\"content\":\"first question\"}," +
                                "{\"role\":\"user\",\"content\":\"second question\"}]}"))
                .andExpect(status().isOk());

        ArgumentCaptor<ChatRequest> reqCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(orchestrator).orchestrate(reqCaptor.capture(), anyString(), any());
        String prompt = reqCaptor.getValue().prompt();
        assertTrue(prompt.contains("be nice"), "应包含 system 段文本");
        assertTrue(prompt.contains("first question"), "应包含首条 user 段文本");
        assertTrue(prompt.contains("second question"), "应包含第二条 user 段文本");
    }

    @Test
    void 非流式_Meta存在时usage取真实token() throws Exception {
        when(orchestrator.orchestrate(any(), anyString(), any())).thenReturn(
                Flux.just(new ChatStreamEvent.Complete("hi",
                        new ChatStreamEvent.Meta("gpt-4o", 12, 34, false))));

        mockMvc.perform(post("/v1/chat/completions")
                        .header("X-API-Key", "sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.prompt_tokens").value(12))
                .andExpect(jsonPath("$.usage.completion_tokens").value(34))
                .andExpect(jsonPath("$.usage.total_tokens").value(46))
                .andExpect(jsonPath("$.model").value("gpt-4o"));
    }

    @Test
    void 鉴权失败返回401_invalid_api_key() throws Exception {
        when(orchestrator.orchestrate(any(), anyString(), any())).thenReturn(
                Flux.just(new ChatStreamEvent.Error("AUTH", "invalid key")));

        mockMvc.perform(post("/v1/chat/completions")
                        .header("X-API-Key", "sk-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"));
    }

    @Test
    void 模型不存在返回400_model_not_found() throws Exception {
        when(orchestrator.orchestrate(any(), anyString(), any())).thenReturn(
                Flux.just(new ChatStreamEvent.Error("MODEL_NOT_FOUND", "no such model")));

        mockMvc.perform(post("/v1/chat/completions")
                        .header("X-API-Key", "sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"nope\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("model_not_found"));
    }

    @Test
    void messages缺失返回400_invalid_messages() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .header("X-API-Key", "sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4o\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_messages"));
    }

    @Test
    void messages为空数组返回400_invalid_messages() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .header("X-API-Key", "sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4o\",\"messages\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_messages"));
    }

    @Test
    void 流式_SSE帧为OpenAI格式() throws Exception {
        when(orchestrator.orchestrate(any(), anyString(), any())).thenReturn(
                Flux.just(
                        new ChatStreamEvent.Delta("Hello "),
                        new ChatStreamEvent.Delta("world"),
                        new ChatStreamEvent.Complete("Hello world")));

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .header("X-API-Key", "sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4o\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andReturn();

        // 异步结果：触发异步派发并等待 SSE 完成
        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk());

        String body = mvcResult.getResponse().getContentAsString();
        // 帧格式：OpenAI 客户端只认裸 data: 行
        assertTrue(body.contains("\"object\":\"chat.completion.chunk\""), "应含 chunk 帧 object 字段");
        // delta.content 存在（regex 更宽松：role 可能为 null 时直接出现 content）
        assertTrue(body.matches("(?s).*\"delta\":\\{\"role\":null,\"content\":\"[^\"]+\"\\}.*")
                        || body.matches("(?s).*\"delta\":\\{\"role\":\"assistant\",\"content\":\"[^\"]+\"\\}.*"),
                "应含 delta.content 字段（content 可在 role 之前或之后）");
        assertTrue(body.contains("[DONE]"), "应以 [DONE] 收尾（data: [DONE] 或 data:[DONE]）");
        // 反向断言：不输出 event: 行（OpenAI SDK 会解析失败）
        assertTrue(!body.contains("event:"), "SSE 帧不应含 event: 行（必须纯 data:）");
    }

    @Test
    void Bearer头可替代XAPIKey() throws Exception {
        when(orchestrator.orchestrate(any(), anyString(), any())).thenReturn(
                Flux.just(new ChatStreamEvent.Complete("ok")));

        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer sk-x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> apiKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(orchestrator).orchestrate(any(), apiKeyCaptor.capture(), any());
        assertTrue("sk-x".equals(apiKeyCaptor.getValue()), "Bearer token 应被剥出为 apiKey");
    }

    @Test
    void embeddings返回501_not_implemented() throws Exception {
        mockMvc.perform(post("/v1/embeddings")
                        .header("X-API-Key", "sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"text-embedding-3-small\",\"input\":\"hi\"}"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.error.type").value("not_implemented"))
                .andExpect(jsonPath("$.error.code").value("not_implemented"));
    }
}