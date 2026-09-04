package com.company.agentgateway.interfaces.chat;

import com.company.agentgateway.domain.iam.AuthChannel;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.Authenticator;
import com.company.agentgateway.domain.orchestration.SessionRepository;
import com.company.agentgateway.domain.session.Session;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.SessionId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SessionApiControllerTest {

    private MockMvc mockMvc;
    private SessionRepository sessionRepository;
    private Authenticator authenticator;

    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal(
            new UserId("u1"), new TenantId("t1"),
            java.util.Set.of(), java.util.Set.of(), AuthChannel.API_KEY);

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        authenticator = mock(Authenticator.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new SessionApiController(sessionRepository, authenticator)).build();
    }

    private Session session() {
        return new Session(new SessionId("s1"), new TenantId("t1"), new UserId("u1"),
                new ModelId("qwen"), Instant.now(), Instant.now(), List.of());
    }

    @Test
    void 创建会话返回sessionId() throws Exception {
        when(authenticator.authenticate("sk-test")).thenReturn(PRINCIPAL);
        when(sessionRepository.create(any(), any(), any())).thenReturn(session());
        mockMvc.perform(post("/v1/sessions").header("X-API-Key", "sk-test").param("model", "qwen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s1"));
    }

    @Test
    void 会话详情返回信息() throws Exception {
        when(authenticator.authenticate("sk-test")).thenReturn(PRINCIPAL);
        when(sessionRepository.load(any())).thenReturn(session());
        mockMvc.perform(get("/v1/sessions/s1").header("X-API-Key", "sk-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s1"))
                .andExpect(jsonPath("$.model").value("qwen"));
    }

    @Test
    void 会话不存在返回404() throws Exception {
        when(authenticator.authenticate("sk-test")).thenReturn(PRINCIPAL);
        when(sessionRepository.load(any())).thenReturn(null);
        mockMvc.perform(get("/v1/sessions/ghost").header("X-API-Key", "sk-test"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 跨租户访问他人会话返回404() throws Exception {
        when(authenticator.authenticate("sk-test")).thenReturn(PRINCIPAL);
        // 会话属于 t2，调用方属于 t1
        when(sessionRepository.load(any())).thenReturn(new Session(new SessionId("s9"),
                new TenantId("t2"), new UserId("u2"), new ModelId("qwen"),
                Instant.now(), Instant.now(), List.of()));
        mockMvc.perform(get("/v1/sessions/s9").header("X-API-Key", "sk-test"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 删除不存在的会话返回404() throws Exception {
        when(authenticator.authenticate("sk-test")).thenReturn(PRINCIPAL);
        when(sessionRepository.load(any())).thenReturn(null);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/v1/sessions/ghost").header("X-API-Key", "sk-test"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 删除本租户会话成功() throws Exception {
        when(authenticator.authenticate("sk-test")).thenReturn(PRINCIPAL);
        when(sessionRepository.load(any())).thenReturn(session());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/v1/sessions/s1").header("X-API-Key", "sk-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value("s1"));
    }

    @Test
    void 消息列表返回数组() throws Exception {
        when(authenticator.authenticate("sk-test")).thenReturn(PRINCIPAL);
        when(sessionRepository.load(any())).thenReturn(session());
        mockMvc.perform(get("/v1/sessions/s1/messages").header("X-API-Key", "sk-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void 会话列表返回数组() throws Exception {
        when(authenticator.authenticate("sk-test")).thenReturn(PRINCIPAL);
        when(sessionRepository.findByUser(any(), any(), anyInt(), anyInt())).thenReturn(List.of(session()));
        mockMvc.perform(get("/v1/sessions").header("X-API-Key", "sk-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].sessionId").value("s1"));
    }
}
