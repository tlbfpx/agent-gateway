package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class RoleBindingTest {

    @Test
    void tripleKey_equalsAndHashCode() {
        RoleBinding a = new RoleBinding(new TenantId("t1"), new UserId("u1"), new RoleId("r1"));
        RoleBinding b = new RoleBinding(new TenantId("t1"), new UserId("u1"), new RoleId("r1"));
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}

class RbacChangeEventTest {

    @Test
    void kind_valuesCoverLifecycle() {
        // spec §GW-RBAC-002 + design §4.2 RbacChangeEvent.Kind
        assertThat(RbacChangeEvent.Kind.values())
                .contains(RbacChangeEvent.Kind.ROLE_UPSERT,
                          RbacChangeEvent.Kind.ROLE_DELETE,
                          RbacChangeEvent.Kind.BIND,
                          RbacChangeEvent.Kind.UNBIND);
    }

    @Test
    void event_carriesAllFields() {
        Instant now = Instant.now();
        RbacChangeEvent ev = new RbacChangeEvent(
                RbacChangeEvent.Kind.ROLE_UPSERT,
                new TenantId("t1"),
                new RoleId("r1"),
                new UserId("u1"),
                "admin",
                now);
        assertThat(ev.kind()).isEqualTo(RbacChangeEvent.Kind.ROLE_UPSERT);
        assertThat(ev.tenant().value()).isEqualTo("t1");
        assertThat(ev.roleId().value()).isEqualTo("r1");
        assertThat(ev.timestamp()).isEqualTo(now);
    }
}
