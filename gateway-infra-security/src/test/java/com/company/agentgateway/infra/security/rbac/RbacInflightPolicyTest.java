package com.company.agentgateway.infra.security.rbac;

import com.company.agentgateway.domain.iam.AgentGrant;
import com.company.agentgateway.domain.iam.AuthChannel;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.iam.AuthorizationException;
import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RbacInflightPolicyTest {

    @Test
    void enforce_passesThrough_whenAuthorized() {
        AuthorizationService svc = new com.company.agentgateway.infra.security.AuthorizationServiceImpl(); // 降级：依赖 principal 字段
        RbacInflightPolicy policy = new RbacInflightPolicy(svc);
        AuthPrincipal p = new AuthPrincipal(new UserId("u1"), new TenantId("t1"),
                Set.of(new AgentGrant("hr-agent", Set.of())), Set.of(), AuthChannel.API_KEY);
        // 不抛
        assertThatCode(() -> policy.enforce(p, "hr-agent")).doesNotThrowAnyException();
    }

    @Test
    void enforce_throwsAuthorizationException_whenDenied() {
        AuthorizationService svc = new com.company.agentgateway.infra.security.AuthorizationServiceImpl();
        RbacInflightPolicy policy = new RbacInflightPolicy(svc);
        AuthPrincipal p = new AuthPrincipal(new UserId("u1"), new TenantId("t1"),
                Set.of(), Set.of(), AuthChannel.API_KEY);
        assertThatThrownBy(() -> policy.enforce(p, "hr-agent"))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("GW-1003"); // RbacErrorCode.UNAUTHORIZED（spec §13.4）
    }
}
