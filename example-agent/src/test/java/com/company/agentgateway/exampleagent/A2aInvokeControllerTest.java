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

        // 轮询等异步完成 (chunk 逐字 sleep(10ms), ~20字符 ≈ 200ms + done)
        // 比硬 Thread.sleep(1200) 稳: 快机 200ms 就完, 慢机最多 5s 超时
        String body = "";
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            body = result.getResponse().getContentAsString();
            if (body.contains("event:done")) break;
            Thread.sleep(50);
        }

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
