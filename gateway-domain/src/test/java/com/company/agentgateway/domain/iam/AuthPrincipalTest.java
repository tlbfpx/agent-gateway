package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.*;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class AuthPrincipalTest {
    @Test
    void canInvokeAgentRespectsGrants() {
        var p = new AuthPrincipal(new UserId("u1"), new TenantId("t1"),
            Set.of(new AgentGrant("hr-agent", Set.of())),
            Set.of(new ModelId("qwen")), AuthChannel.API_KEY);
        assertThat(p.canInvoke("hr-agent")).isTrue();
        assertThat(p.canInvoke("finance-agent")).isFalse();
    }

    @Test
    void canUseModelRespectsAllowedModels() {
        var p = new AuthPrincipal(new UserId("u1"), new TenantId("t1"), Set.of(),
            Set.of(new ModelId("qwen")), AuthChannel.API_KEY);
        assertThat(p.canUse(new ModelId("qwen"))).isTrue();
        assertThat(p.canUse(new ModelId("glm"))).isFalse();
    }
}
