package com.company.agentgateway.infra.security;

import com.company.agentgateway.domain.iam.AgentGrant;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.AuthenticationException;
import com.company.agentgateway.domain.iam.AuthorizationException;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * spec §6.2 二期：多租户切换认证。
 *
 * <p>场景：Key 绑定多个授权租户；带 X-Tenant-Id 时校验 target 在列表内才能切换。
 */
class TenantAwareAuthenticatorTest {

    private InMemoryApiKeyStore store;
    private TenantAwareAuthenticator authenticator;

    private static final String KEY = "sk-multi";
    private static final TenantId PRIMARY = new TenantId("t1");
    private static final TenantId SECONDARY = new TenantId("t2");
    private static final TenantId OTHER = new TenantId("t3");
    private static final UserId U = new UserId("u1");

    @BeforeEach
    void setUp() {
        store = new InMemoryApiKeyStore();
        authenticator = new TenantAwareAuthenticator(store);
        // 授权租户：主租户 t1 + 副租户 t2（t3 不在列表）
        LinkedHashSet<TenantId> tenants = new LinkedHashSet<>();
        tenants.add(PRIMARY);
        tenants.add(SECONDARY);
        store.register(KEY, new ApiKeyStore.ApiKeyBinding(
                PRIMARY, U,
                Set.of(new AgentGrant("hr-agent", Set.of())),
                Set.of(new ModelId("qwen")),
                false, tenants));
    }

    @Test
    void 不带tenantId_得主租户() {
        AuthPrincipal p = authenticator.authenticate(KEY, null);
        assertThat(p.tenant()).isEqualTo(PRIMARY);
        assertThat(p.user()).isEqualTo(U);
    }

    @Test
    void 带空白tenantId_等同主租户() {
        AuthPrincipal p = authenticator.authenticate(KEY, "   ");
        assertThat(p.tenant()).isEqualTo(PRIMARY);
    }

    @Test
    void 带授权内副租户_切换成功() {
        AuthPrincipal p = authenticator.authenticate(KEY, "t2");
        assertThat(p.tenant()).isEqualTo(SECONDARY);
        assertThat(p.user()).isEqualTo(U); // user 上下文不变
    }

    @Test
    void 带主租户_仍得主租户() {
        AuthPrincipal p = authenticator.authenticate(KEY, "t1");
        assertThat(p.tenant()).isEqualTo(PRIMARY);
    }

    @Test
    void 带未授权租户_抛AuthorizationException() {
        assertThatThrownBy(() -> authenticator.authenticate(KEY, "t3"))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("not authorized for tenant");
    }

    @Test
    void 无效key_抛AuthenticationException() {
        assertThatThrownBy(() -> authenticator.authenticate("sk-bad", "t2"))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void 单参authenticate仍可用_与一期兼容() {
        AuthPrincipal p = authenticator.authenticate(KEY);
        assertThat(p.tenant()).isEqualTo(PRIMARY);
    }

    @Test
    void 实现MultiTenantAuthenticator_可被domain端口识别() {
        assertThat(authenticator instanceof com.company.agentgateway.domain.iam.MultiTenantAuthenticator)
                .as("TenantAwareAuthenticator 必须实现 domain 端口")
                .isTrue();
    }
}