package com.company.agentgateway.interfaces.chat;

import com.company.agentgateway.domain.iam.AuthChannel;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.Authenticator;
import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.orchestration.AgentCardPort;
import com.company.agentgateway.domain.registry.AgentCard;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DiscoveryApiControllerTest {

    private MockMvc mockMvc;
    private AgentCardPort agentCardPort;
    private Authenticator authenticator;
    private AuthorizationService authorizationService;
    private com.company.agentgateway.infra.llm.model.ModelRegistry modelRegistry;

    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal(
            new UserId("u1"), new TenantId("t1"),
            java.util.Set.of(), Set.of(new ModelId("qwen")), AuthChannel.API_KEY);

    @BeforeEach
    void setUp() {
        agentCardPort = mock(AgentCardPort.class);
        authenticator = mock(Authenticator.class);
        authorizationService = mock(AuthorizationService.class);
        modelRegistry = mock(com.company.agentgateway.infra.llm.model.ModelRegistry.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new DiscoveryApiController(agentCardPort, authenticator, authorizationService, modelRegistry)).build();
    }

    @Test
    void agents_返回授权Agent列表() throws Exception {
        when(authenticator.authenticate("sk-test")).thenReturn(PRINCIPAL);
        when(agentCardPort.snapshot()).thenReturn(List.of(
                new AgentCard("hr", "HR", List.of(), "{}", "{}", "1", true, "u")));
        when(authorizationService.canInvokeAgent(any(), any())).thenReturn(true);
        mockMvc.perform(get("/v1/agents").header("X-API-Key", "sk-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("hr"));
    }

    @Test
    void agents_过滤无权限Agent() throws Exception {
        when(authenticator.authenticate("sk-test")).thenReturn(PRINCIPAL);
        when(agentCardPort.snapshot()).thenReturn(List.of(
                new AgentCard("hr", "HR", List.of(), "{}", "{}", "1", true, "u"),
                new AgentCard("fin", "财务", List.of(), "{}", "{}", "1", true, "u")));
        when(authorizationService.canInvokeAgent(any(), any())).thenAnswer(
                inv -> "hr".equals(inv.getArgument(1, String.class)));
        mockMvc.perform(get("/v1/agents").header("X-API-Key", "sk-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void models_返回管理员配置的启用模型() throws Exception {
        when(authenticator.authenticate("sk-test")).thenReturn(PRINCIPAL);
        var def = new com.company.agentgateway.domain.model.ModelDef(
                new com.company.agentgateway.domain.shared.ModelId("qwen"),
                "minimax", "MiniMax", "https://x", "sk-1",
                java.util.Set.of(), 8192, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                true, java.util.List.of("all"), null);
        when(modelRegistry.listModels()).thenReturn(java.util.List.of(def));
        mockMvc.perform(get("/v1/models").header("X-API-Key", "sk-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].modelId").value("qwen"))
                .andExpect(jsonPath("$[0].displayName").value("MiniMax"));
    }
}
