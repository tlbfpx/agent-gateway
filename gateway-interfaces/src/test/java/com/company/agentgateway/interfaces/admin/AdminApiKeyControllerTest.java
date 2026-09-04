package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.infra.security.ApiKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminApiKeyController 输入校验回归：tenant/user 缺失、非法 expiresAt → 400（而非 500）。
 */
class AdminApiKeyControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AdminApiKeyController(mock(ApiKeyStore.class), mock(AuditRepository.class))).build();
    }

    @Test
    void 签发成功返回完整key() throws Exception {
        mockMvc.perform(post("/v1/admin/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant\":\"t1\",\"user\":\"u1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").exists())
                .andExpect(jsonPath("$.tenant").value("t1"));
    }

    @Test
    void 缺少tenant返回400() throws Exception {
        mockMvc.perform(post("/v1/admin/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user\":\"u1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 缺少user返回400() throws Exception {
        mockMvc.perform(post("/v1/admin/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant\":\"t1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 非法expiresAt返回400() throws Exception {
        mockMvc.perform(post("/v1/admin/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant\":\"t1\",\"user\":\"u1\",\"expiresAt\":\"not-a-date\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 列表返回数组() throws Exception {
        mockMvc.perform(get("/v1/admin/api-keys").header("X-API-Key", "sk-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
