package com.company.agentgateway.interfaces.chat;

import com.company.agentgateway.application.orchestration.ChatOrchestrator;
import com.company.agentgateway.application.orchestration.ChatRequest;
import com.company.agentgateway.application.orchestration.ChatStreamEvent;
import com.company.agentgateway.infra.observability.trace.GatewayTracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatControllerTest {

    private MockMvc mockMvc;
    private ChatOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = mock(ChatOrchestrator.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ChatController(orchestrator, GatewayTracer.NOOP)).build();
    }

    @Test
    void 非流式chat_返回完整响应() throws Exception {
        when(orchestrator.orchestrate(any(), anyString(), any())).thenReturn(
                Flux.just(new ChatStreamEvent.Delta("Hello"), new ChatStreamEvent.Complete("Hello")));

        mockMvc.perform(post("/v1/chat")
                        .header("X-API-Key", "sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").value("Hello"));
    }

    @Test
    void 非流式chat_error事件返回error() throws Exception {
        when(orchestrator.orchestrate(any(), anyString(), any())).thenReturn(
                Flux.just(new ChatStreamEvent.Error("AUTH", "invalid key")));

        mockMvc.perform(post("/v1/chat")
                        .header("X-API-Key", "sk-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 非流式chat_prompt缺失返回400() throws Exception {
        mockMvc.perform(post("/v1/chat")
                        .header("X-API-Key", "sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 非流式chat_prompt空白返回400() throws Exception {
        mockMvc.perform(post("/v1/chat")
                        .header("X-API-Key", "sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 非流式chat_prompt超长返回400() throws Exception {
        mockMvc.perform(post("/v1/chat")
                        .header("X-API-Key", "sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"" + "a".repeat(40_000) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 流式chat_prompt缺失返回400() throws Exception {
        mockMvc.perform(post("/v1/chat/stream")
                        .header("X-API-Key", "sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
