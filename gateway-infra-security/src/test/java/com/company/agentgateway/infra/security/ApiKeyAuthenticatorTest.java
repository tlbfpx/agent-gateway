package com.company.agentgateway.infra.security;

import com.company.agentgateway.domain.iam.AgentGrant;
import com.company.agentgateway.domain.iam.AuthChannel;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.AuthenticationException;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyAuthenticatorTest {

    private InMemoryApiKeyStore store;
    private ApiKeyAuthenticator authenticator;

    private static final String KEY = "sk-test-123";
    private static final TenantId T = new TenantId("t1");
    private static final UserId U = new UserId("u1");

    @BeforeEach
    void setUp() {
        store = new InMemoryApiKeyStore();
        authenticator = new ApiKeyAuthenticator(store);
        store.register(KEY, new ApiKeyStore.ApiKeyBinding(
                T, U,
                Set.of(new AgentGrant("hr-agent", Set.of())),
                Set.of(new ModelId("qwen")),
                false));
    }

    @Test
    void 有效key返回AuthPrincipal_channel为API_KEY() {
        AuthPrincipal p = authenticator.authenticate(KEY);
        assertThat(p.user()).isEqualTo(U);
        assertThat(p.tenant()).isEqualTo(T);
        assertThat(p.channel()).isEqualTo(AuthChannel.API_KEY);
        assertThat(p.agentGrants()).hasSize(1);
        assertThat(p.allowedModels()).contains(new ModelId("qwen"));
    }

    @Test
    void 无效key抛AuthenticationException() {
        assertThatThrownBy(() -> authenticator.authenticate("sk-wrong"))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("Invalid or missing API key");
    }

    @Test
    void 空key抛异常() {
        assertThatThrownBy(() -> authenticator.authenticate(""))
                .isInstanceOf(AuthenticationException.class);
        assertThatThrownBy(() -> authenticator.authenticate(null))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void 吊销key抛异常() {
        store.revoke(KEY);
        assertThatThrownBy(() -> authenticator.authenticate(KEY))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void revoked标记为true的key被拒绝() {
        store.register("sk-revoked", new ApiKeyStore.ApiKeyBinding(
                T, U, Set.of(), Set.of(), true));
        assertThatThrownBy(() -> authenticator.authenticate("sk-revoked"))
                .isInstanceOf(AuthenticationException.class);
    }
}
