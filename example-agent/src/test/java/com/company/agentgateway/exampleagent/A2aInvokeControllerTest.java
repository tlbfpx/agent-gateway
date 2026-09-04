package com.company.agentgateway.exampleagent;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * A2A 端点契约测试：POST /a2a/invoke/{agent} 返回 SSE 流（chunk...+done）。
 */
class A2aInvokeControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ExampleAgentApplication.A2aInvokeController())
            .build();

    @Test
    void 调用返回SSE流_含chunk与done() throws Exception {
        MvcResult result = mockMvc.perform(post("/a2a/invoke/echo-agent")
                        .contentType("application/json")
                        .content("{\"jsonrpc\":\"2.0\",\"method\":\"invoke\",\"params\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        // 等异步完成（SseEmitter 异步写）
        mockMvc.perform(post("/a2a/nonexistent")); // no-op；用 await 方式更稳：
        Thread.sleep(1200); // chunk 逐字 sleep(10ms)，"hello [echoed...]" ~20字符 ≈ 200ms + done
        String body = result.getResponse().getContentAsString();

        assertThat(body).contains("event:chunk");
        assertThat(body).contains("event:done");
        assertThat(body).contains("[echoed by example-agent]");
    }

    @Test
    void content_type为SSE() throws Exception {
        mockMvc.perform(post("/a2a/invoke/echo-agent")
                        .contentType("application/json")
                        .content("{\"params\":\"x\"}"))
                .andExpect(content().contentType(org.springframework.http.MediaType.TEXT_EVENT_STREAM));
    }
}
